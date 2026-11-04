package io.github.samhom.driverkit.redis;

import static org.junit.jupiter.api.Assertions.*;

import io.github.samhom.driverkit.core.*;
import io.github.samhom.driverkit.core.Endpoint;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.*;
import redis.clients.jedis.util.JedisClusterCRC16;

/** Real Jedis routing against a bounded two-node RESP fixture, not a mocked executor. */
class TopologyRoutingTest {
  static final ResourceId ID = new ResourceId("topology", "test", "cache");
  static final Duration FIVE = Duration.ofSeconds(5);

  static ManagedClient<UnifiedJedis, RedisTopologySettings> register(
      ClientRuntime runtime, Fixture fixture) {
    return runtime.register(
        new ConfigSnapshot<>(ID, 1, fixture.settings()),
        new RedisTopologyAdapter(),
        new ClientPolicy(32, FIVE, FIVE));
  }

  static void eventually(BooleanSupplier condition) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
    assertTrue(condition.getAsBoolean());
  }

  @Test
  void movedAndAskFollowExplicitRejectionsWithoutDuplicatingMutation() throws Exception {
    try (Fixture fixture = new Fixture();
        ClientRuntime runtime = new ClientRuntime()) {
      var handle = register(runtime, fixture);
      var redis = new ManagedTopologyRedis(handle, Duration.ofSeconds(2), 1024);
      assertEquals(1, redis.increment("ask"));
      assertEquals(2, redis.increment("ask"));
      assertEquals(2, fixture.first.askRedirects.get());
      assertEquals(2, fixture.second.asking.get());
      assertEquals(2, fixture.second.mutations.get());
      assertEquals(0, fixture.first.mutations.get());
      assertEquals("moved-value", redis.get("moved"));
      assertEquals(1, fixture.first.movedRedirects.get());
      assertEquals("moved-value", redis.get("moved"));
      assertEquals(2, fixture.second.movedReads.get());
    }
  }

  @Test
  void failedMovedRefreshDoesNotBorrowItsOwnExhaustedPoolAsAFallback() throws Exception {
    try (Fixture fixture = new Fixture();
        ClientRuntime runtime = new ClientRuntime()) {
      var handle = register(runtime, fixture);
      var redis = new ManagedTopologyRedis(handle, Duration.ofMillis(150), 1024);
      fixture.first.rejectMovedRefresh.set(true);
      assertThrows(ClientException.class, () -> redis.get("moved"));
      eventually(() -> handle.status().inFlight() == 0);
      assertEquals(1, fixture.first.movedRedirects.get());
      assertEquals(1, fixture.first.rejectedRefreshes.get());
      assertEquals(0, fixture.second.movedReads.get());
    }
  }

  @Test
  void socketLossAfterServerAppliedIncrementIsUnknownAndIsNeverReplayed() throws Exception {
    try (Fixture fixture = new Fixture();
        ClientRuntime runtime = new ClientRuntime()) {
      var redis = new ManagedTopologyRedis(register(runtime, fixture), Duration.ofSeconds(2), 1024);
      ClientException failure = assertThrows(ClientException.class, () -> redis.increment("lost"));
      assertEquals(ClientException.Kind.WRITE_OUTCOME_UNKNOWN, failure.kind());
      assertEquals(1, fixture.first.lostWrites.get());
      assertEquals(0, fixture.second.lostWrites.get());
      assertNotNull(failure.getCause());
    }
  }

  @Test
  void shortFacadeDeadlineEndsPhysicalPoolWaitAndSocketRead() throws Exception {
    try (Fixture fixture = new Fixture();
        ClientRuntime runtime = new ClientRuntime()) {
      var handle = register(runtime, fixture);
      var redis = new ManagedTopologyRedis(handle, Duration.ofMillis(150), 1024);
      try (var session = handle.session();
          Connection held =
              ((RedisClusterClient) session.client())
                  .getConnectionFromSlot(JedisClusterCRC16.getSlot("waiting"))) {
        assertThrows(ClientException.class, () -> redis.get("waiting"));
        eventually(() -> handle.status().inFlight() == 1);
      }
      try {
        assertThrows(ClientException.class, () -> redis.get("slow"));
        assertTrue(fixture.first.slowEntered.await(1, TimeUnit.SECONDS));
        eventually(() -> handle.status().inFlight() == 0);
      } finally {
        fixture.first.slowRelease.countDown();
      }
      assertNull(redis.get("recovered"));
    }
  }

  @Test
  void independentSentinelCredentialVersionCannotUseAnOldGeneration() throws Exception {
    try (Fixture fixture = new Fixture()) {
      var adapter = new RedisTopologyAdapter();
      CredentialsProvider resolver = fixture.credentials();
      Instant expires = Instant.now().plusSeconds(60);
      var source =
          new RotatingCredentialsProvider(
              resolver, new CredentialState(1, expires.minusSeconds(5), expires, false), () -> {});
      var settings =
          new RedisTopologySettings(
              RedisTopologySettings.Mode.SENTINEL,
              Set.of(fixture.first.endpoint()),
              "master",
              resolver,
              source,
              FIVE,
              FIVE,
              1);
      try (UnifiedJedis client = adapter.create(settings)) {
        adapter.warmup(client, FIVE);
        assertNull(client.get("before"));
        source.rotate(resolver, new CredentialState(2, expires.minusSeconds(5), expires, false));
        ClientException failure = assertThrows(ClientException.class, () -> client.get("stale"));
        assertEquals(ClientException.Kind.UNAVAILABLE, failure.kind());
        assertEquals(0, fixture.first.staleReads.get());
      }
      try (UnifiedJedis replacement = adapter.create(settings)) {
        adapter.warmup(replacement, FIVE);
        assertNull(replacement.get("stale"));
        assertEquals(1, fixture.first.staleReads.get());
      }
    }
  }

  static final class Fixture implements AutoCloseable {
    final String password = UUID.randomUUID().toString();
    final Node first, second;

    Fixture() throws IOException {
      first = new Node(this, false);
      second = new Node(this, true);
    }

    CredentialsProvider credentials() {
      return () -> new Credentials("", password.toCharArray());
    }

    RedisTopologySettings settings() {
      return new RedisTopologySettings(
          RedisTopologySettings.Mode.CLUSTER,
          Set.of(first.endpoint()),
          null,
          credentials(),
          credentials(),
          FIVE,
          FIVE,
          1);
    }

    public void close() {
      first.close();
      second.close();
    }
  }

  static final class Node implements AutoCloseable {
    final Fixture fixture;
    final boolean target;
    final ServerSocket listener;
    final ExecutorService acceptor = Executors.newSingleThreadExecutor();
    final ThreadPoolExecutor workers =
        new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    final CountDownLatch slowEntered = new CountDownLatch(1), slowRelease = new CountDownLatch(1);
    final AtomicInteger askRedirects = new AtomicInteger(),
        asking = new AtomicInteger(),
        mutations = new AtomicInteger();
    final AtomicInteger movedRedirects = new AtomicInteger(),
        movedReads = new AtomicInteger(),
        lostWrites = new AtomicInteger(),
        staleReads = new AtomicInteger();
    final AtomicBoolean advertiseTarget = new AtomicBoolean(),
        rejectMovedRefresh = new AtomicBoolean();
    final AtomicInteger rejectedRefreshes = new AtomicInteger();

    Node(Fixture fixture, boolean target) throws IOException {
      this.fixture = fixture;
      this.target = target;
      listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
      acceptor.submit(
          () -> {
            while (!listener.isClosed())
              try {
                Socket socket = listener.accept();
                socket.setSoTimeout(5000);
                sockets.add(socket);
                try {
                  workers.execute(() -> serve(socket));
                } catch (RejectedExecutionException full) {
                  sockets.remove(socket);
                  socket.close();
                }
              } catch (IOException closed) {
                break;
              }
          });
    }

    Endpoint endpoint() {
      return new Endpoint("127.0.0.1", listener.getLocalPort(), false);
    }

    void serve(Socket socket) {
      boolean authenticated = false, asked = false;
      try (socket;
          InputStream input = socket.getInputStream();
          OutputStream output = socket.getOutputStream()) {
        for (List<String> command; (command = read(input)) != null; ) {
          String name = command.get(0).toUpperCase(Locale.ROOT);
          if (name.equals("AUTH")) {
            authenticated = fixture.password.equals(command.get(command.size() - 1));
            write(output, authenticated ? "+OK\r\n" : "-WRONGPASS fixture\r\n");
            continue;
          }
          if (!authenticated) {
            write(output, "-NOAUTH fixture\r\n");
            continue;
          }
          switch (name) {
            case "CLIENT":
              write(output, "+OK\r\n");
              break;
            case "PING":
              write(output, "+PONG\r\n");
              break;
            case "CLUSTER":
              {
                if (advertiseTarget.get() && rejectMovedRefresh.get()) {
                  rejectedRefreshes.incrementAndGet();
                  write(output, "-ERR injected refresh rejection\r\n");
                  break;
                }
                Node assigned = advertiseTarget.get() ? fixture.second : fixture.first;
                // Native CLUSTER SLOTS decoder consumes all slots, primary endpoint and a node ID.
                write(
                    output,
                    "*1\r\n*3\r\n:0\r\n:16383\r\n*3\r\n"
                        + bulk("127.0.0.1")
                        + ":"
                        + assigned.listener.getLocalPort()
                        + "\r\n"
                        + bulk("0".repeat(40)));
                break;
              }
            case "SENTINEL":
              write(
                  output,
                  "*2\r\n"
                      + bulk("127.0.0.1")
                      + bulk(Integer.toString(fixture.first.listener.getLocalPort())));
              break;
            case "SUBSCRIBE":
              write(output, "*3\r\n" + bulk("subscribe") + bulk(command.get(1)) + ":1\r\n");
              break;
            case "ASKING":
              asking.incrementAndGet();
              asked = true;
              write(output, "+OK\r\n");
              break;
            case "GET":
              {
                String key = command.get(1);
                if (key.equals("moved") && !target) {
                  movedRedirects.incrementAndGet();
                  advertiseTarget.set(true);
                  write(
                      output,
                      "-MOVED "
                          + JedisClusterCRC16.getSlot(key)
                          + " 127.0.0.1:"
                          + fixture.second.listener.getLocalPort()
                          + "\r\n");
                } else if (key.equals("moved")) {
                  movedReads.incrementAndGet();
                  write(output, bulk("moved-value"));
                } else if (key.equals("slow")) {
                  slowEntered.countDown();
                  slowRelease.await(5, TimeUnit.SECONDS);
                  write(output, "$-1\r\n");
                } else {
                  if (key.equals("stale")) staleReads.incrementAndGet();
                  write(output, "$-1\r\n");
                }
                break;
              }
            case "INCR":
              {
                String key = command.get(1);
                if (key.equals("lost")) {
                  lostWrites.incrementAndGet();
                  return;
                }
                if (key.equals("ask") && !target) {
                  askRedirects.incrementAndGet();
                  write(
                      output,
                      "-ASK "
                          + JedisClusterCRC16.getSlot(key)
                          + " 127.0.0.1:"
                          + fixture.second.listener.getLocalPort()
                          + "\r\n");
                } else if (key.equals("ask") && !asked) write(output, "-ERR ASKING required\r\n");
                else {
                  asked = false;
                  write(output, ":" + mutations.incrementAndGet() + "\r\n");
                }
                break;
              }
            default:
              write(output, "-ERR fixture unsupported command\r\n");
          }
        }
      } catch (IOException | InterruptedException closed) {
        if (closed instanceof InterruptedException) Thread.currentThread().interrupt();
      } finally {
        sockets.remove(socket);
      }
    }

    static String bulk(String value) {
      return "$" + value.getBytes(StandardCharsets.UTF_8).length + "\r\n" + value + "\r\n";
    }

    static void write(OutputStream output, String value) throws IOException {
      output.write(value.getBytes(StandardCharsets.UTF_8));
      output.flush();
    }

    static String line(InputStream input) throws IOException {
      ByteArrayOutputStream value = new ByteArrayOutputStream();
      for (int c; (c = input.read()) != -1; ) {
        if (c == '\r') {
          if (input.read() != '\n') throw new IOException("Invalid line");
          return value.toString(StandardCharsets.UTF_8);
        }
        value.write(c);
        if (value.size() > 65536) throw new IOException("Oversized fixture line");
      }
      return null;
    }

    static List<String> read(InputStream input) throws IOException {
      String header = line(input);
      if (header == null) return null;
      if (!header.startsWith("*")) throw new IOException("Expected array");
      int count = Integer.parseInt(header.substring(1));
      if (count < 1 || count > 100) throw new IOException("Array bounds");
      List<String> command = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        String bulk = line(input);
        if (bulk == null || !bulk.startsWith("$")) throw new IOException("Expected bulk");
        int size = Integer.parseInt(bulk.substring(1));
        if (size < 0 || size > 65536) throw new IOException("Bulk bounds");
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size || input.read() != '\r' || input.read() != '\n')
          throw new EOFException();
        command.add(new String(bytes, StandardCharsets.UTF_8));
      }
      return command;
    }

    public void close() {
      slowRelease.countDown();
      try {
        listener.close();
      } catch (IOException ignored) {
      }
      for (Socket socket : sockets)
        try {
          socket.close();
        } catch (IOException ignored) {
        }
      acceptor.shutdownNow();
      workers.shutdownNow();
      try {
        acceptor.awaitTermination(2, TimeUnit.SECONDS);
        workers.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
