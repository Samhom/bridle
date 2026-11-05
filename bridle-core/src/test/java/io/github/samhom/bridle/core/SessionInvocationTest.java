package io.github.samhom.bridle.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class SessionInvocationTest {
  static final ResourceId ID = new ResourceId("session", "test", "resource");

  static void eventually(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
    assertTrue(condition.getAsBoolean());
  }

  @Test
  void pinnedCallsUseOneGenerationAndKeepPhysicalOwnershipAfterLogicalTimeout() throws Exception {
    Queue<String> closed = new ConcurrentLinkedQueue<>();
    Queue<Long> observed = new ConcurrentLinkedQueue<>();
    Observer observer = (r, d, o, revision) -> (outcome, elapsed) -> observed.add(revision);
    try (ClientRuntime runtime = new ClientRuntime(2, 1, 8, observer)) {
      var adapter =
          new FactoryAdapter<String, String>("fixture", s -> s, (c, t) -> {}, closed::add);
      var client =
          runtime.register(new ConfigSnapshot<>(ID, 1, "old"), adapter, ClientPolicy.defaults());
      var other =
          runtime.register(
              new ConfigSnapshot<>(new ResourceId("session", "test", "other"), 1, "other"),
              adapter,
              ClientPolicy.defaults());
      var lease = client.session();
      CompletableFuture<String> physical = new CompletableFuture<>();
      try {
        assertEquals(UpdateResult.APPLIED, client.reload(new ConfigSnapshot<>(ID, 2, "new")));
        assertEquals(
            "old",
            client.executeAwait(
                lease,
                Operation.read("pinned", Duration.ofSeconds(1)),
                (nativeClient, context) -> {
                  assertEquals("old", context.settings(String.class));
                  return CompletableFuture.completedFuture(nativeClient);
                }));
        assertEquals(1L, observed.poll());
        assertThrows(
            IllegalArgumentException.class,
            () ->
                other.executeAwait(
                    lease,
                    Operation.read("foreign", Duration.ofSeconds(1)),
                    (c, ctx) -> CompletableFuture.completedFuture(c)));
        CountDownLatch entered = new CountDownLatch(1);
        var result =
            client.executeAsync(
                lease,
                Operation.read("pending", Duration.ofMillis(50)),
                (c, ctx) -> {
                  entered.countDown();
                  return physical;
                });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        assertEquals(ClientException.Kind.TIMEOUT, ((ClientException) failure.getCause()).kind());
        lease.close();
        assertEquals(1, client.status().retiringGenerations());
        assertFalse(closed.contains("old"));
        physical.complete("late");
        eventually(() -> closed.contains("old"));
      } finally {
        physical.complete("late");
        lease.close();
      }
    }
  }

  record Settings(CredentialsProvider credentials) implements ImmutableSettings {}

  @Test
  void supersededCredentialsAndRevocationRejectNewWorkThroughOldSessions() throws Exception {
    CredentialsProvider provider = () -> new Credentials("fixture", new char[0]);
    var state =
        new CredentialState(1, Instant.now().plusSeconds(20), Instant.now().plusSeconds(30), false);
    var source = new RotatingCredentialsProvider(provider, state, () -> {});
    DriverAdapter<String, Settings> adapter =
        new DriverAdapter<>() {
          public String id() {
            return "fixture";
          }

          public void validate(Settings s) {}

          public CredentialsProvider credentials(Settings s) {
            return s.credentials();
          }

          public String create(Settings s) {
            return "native";
          }

          public void warmup(String c, Duration d) {}

          public void close(String c) {}
        };
    try (ClientRuntime runtime = new ClientRuntime()) {
      var client =
          runtime.register(
              new ConfigSnapshot<>(ID, 1, new Settings(source)), adapter, ClientPolicy.defaults());
      client.bindCredentials(source);
      eventually(() -> client.credentialStatus().state() == CredentialStatus.State.ACTIVE);
      try (var lease = client.session()) {
        source.rotate(
            provider, new CredentialState(2, state.refreshAt(), state.expiresAt(), false));
        eventually(() -> client.credentialStatus().activeVersion() == 2);
        var failure =
            assertThrows(
                ClientException.class,
                () ->
                    client.executeAwait(
                        lease,
                        Operation.read("old", Duration.ofSeconds(1)),
                        (c, ctx) -> CompletableFuture.completedFuture(c)));
        assertEquals(ClientException.Kind.REVOKED, failure.kind());
        assertThrows(ClientException.class, lease::client);
      }
      try (var lease = client.session()) {
        client.revoke();
        assertThrows(
            ClientException.class,
            () ->
                client.executeAwait(
                    lease,
                    Operation.read("revoked", Duration.ofSeconds(1)),
                    (c, ctx) -> CompletableFuture.completedFuture(c)));
        assertThrows(ClientException.class, lease::client);
      }
    }
  }
}
