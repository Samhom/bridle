package io.github.samhom.bridle.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Seeded concurrent schedules with a separate native-resource ownership oracle. */
@Tag("stability")
class LifecycleModelStressTest {
  private static final Duration BUDGET = Duration.ofSeconds(5);
  private static final int SLOTS = 3, WORKERS = 4;
  private static final Set<ClientException.Kind> ADMISSION_FAILURES =
      EnumSet.of(
          ClientException.Kind.CLOSED,
          ClientException.Kind.REVOKED,
          ClientException.Kind.OVERLOADED,
          ClientException.Kind.TIMEOUT,
          ClientException.Kind.UPDATE_REJECTED);

  static final class NativeResource {
    final long revision;
    final AtomicInteger closes = new AtomicInteger();
    int users;
    boolean closed;

    NativeResource(long revision) {
      this.revision = revision;
    }

    long read() {
      synchronized (this) {
        if (closed) throw new AssertionError("Native work started after close");
        users++;
      }
      try {
        Thread.yield();
        Thread.yield();
        return revision;
      } finally {
        synchronized (this) {
          if (closed) throw new AssertionError("Native resource closed during work");
          users--;
        }
      }
    }

    synchronized void close() {
      if (closes.incrementAndGet() != 1) throw new AssertionError("Native close was duplicated");
      if (users != 0) throw new AssertionError("Native cleanup raced physical work");
      closed = true;
    }
  }

  static final class Oracle implements DriverAdapter<NativeResource, Long> {
    final Queue<NativeResource> created = new ConcurrentLinkedQueue<>();

    public String id() {
      return "model-fixture";
    }

    public void validate(Long revision) {
      if (revision < 1) throw new IllegalArgumentException();
    }

    public NativeResource create(Long revision) {
      NativeResource resource = new NativeResource(revision);
      created.add(resource);
      return resource;
    }

    public void warmup(NativeResource resource, Duration timeout) {
      // A failed candidate is still owned and must eventually be closed exactly once.
      if (resource.revision % 17 == 0)
        throw new IllegalStateException("Injected readiness failure");
      Thread.yield();
    }

    public void close(NativeResource resource) {
      resource.close();
    }
  }

  static ResourceId id(int slot) {
    return new ResourceId("model", "test", "resource" + slot);
  }

  static ConfigSnapshot<Long> config(int slot, long revision) {
    return new ConfigSnapshot<>(id(slot), revision, revision);
  }

  static int boundedProperty(String name, int fallback, int maximum) {
    int value = Integer.getInteger(name, fallback);
    if (value < 1 || value > maximum) throw new IllegalArgumentException(name + " out of bounds");
    return value;
  }

  static Set<Long> sdkThreads() {
    Set<Long> ids = new HashSet<>();
    for (Thread thread : Thread.getAllStackTraces().keySet())
      if (thread.isAlive() && thread.getName().startsWith("bridle-")) ids.add(thread.getId());
    return ids;
  }

  static void eventually(BooleanSupplier condition, String description) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
    assertTrue(condition.getAsBoolean(), description);
  }

  static void allowed(ClientException failure) {
    assertTrue(
        ADMISSION_FAILURES.contains(failure.kind()),
        () -> "Unexpected SDK failure: " + failure.kind() + ", cause=" + failure.getCause());
  }

  @Test
  void seededSchedulesPreserveOwnershipRevisionAndRegistryInvariants() throws Exception {
    int rounds = boundedProperty("bridle.stress.rounds", 6, 10000);
    int steps = boundedProperty("bridle.stress.steps", 80, 10000);
    long initialSeed = Long.getLong("bridle.stress.seed", 0x5eed26L);
    Set<Long> threadsBefore = sdkThreads();
    for (int round = 0; round < rounds; round++) {
      long seed = initialSeed + round;
      try {
        runRound(seed, steps);
      } catch (Throwable failure) {
        throw new AssertionError(
            "Reproduce with -Dbridle.stress.seed="
                + seed
                + " -Dbridle.stress.rounds=1 -Dbridle.stress.steps="
                + steps,
            failure);
      }
      eventually(
          () -> {
            Set<Long> remaining = sdkThreads();
            remaining.removeAll(threadsBefore);
            return remaining.isEmpty();
          },
          "SDK threads leaked after completed round " + round);
    }
  }

  private static void runRound(long seed, int steps) throws Exception {
    Oracle oracle = new Oracle();
    AtomicReferenceArray<ManagedClient<NativeResource, Long>> slots =
        new AtomicReferenceArray<>(SLOTS);
    AtomicLongArray revisions = new AtomicLongArray(SLOTS);
    Queue<ManagedClient<NativeResource, Long>> handles = new ConcurrentLinkedQueue<>();
    Map<ManagedClient<NativeResource, Long>, Long> lastObserved = new IdentityHashMap<>();
    AtomicInteger calls = new AtomicInteger(), updates = new AtomicInteger();
    ExecutorService callers = Executors.newFixedThreadPool(WORKERS);
    ExecutorService nativeWork = Executors.newFixedThreadPool(2);
    ClientRuntime runtime = new ClientRuntime(SLOTS, 2, 32, Observer.NOOP);
    ClientPolicy policy = new ClientPolicy(32, BUDGET, BUDGET, ExecutionPolicy.isolated(1, 16));
    try {
      for (int i = 0; i < SLOTS; i++) {
        revisions.set(i, 1);
        ManagedClient<NativeResource, Long> client = runtime.register(config(i, 1), oracle, policy);
        slots.set(i, client);
        handles.add(client);
      }
      CountDownLatch ready = new CountDownLatch(WORKERS), start = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();
      for (int worker = 0; worker < WORKERS; worker++) {
        final int index = worker;
        futures.add(
            callers.submit(
                () -> {
                  Random random = new Random(seed ^ (0x9e3779b97f4a7c15L * (index + 1)));
                  ready.countDown();
                  try {
                    start.await();
                    for (int step = 0; step < steps; step++) {
                      int slot = random.nextInt(SLOTS);
                      ManagedClient<NativeResource, Long> client = slots.get(slot);
                      if (client == null) {
                        long revision = revisions.incrementAndGet(slot);
                        // Avoid an injected failure in registration; failures are exercised in
                        // reloads.
                        if (revision % 17 == 0) revision = revisions.incrementAndGet(slot);
                        try {
                          ManagedClient<NativeResource, Long> replacement =
                              runtime.register(config(slot, revision), oracle, policy);
                          handles.add(replacement);
                          if (!slots.compareAndSet(slot, null, replacement)) {
                            replacement.close();
                            throw new AssertionError("Two registrations acquired one slot");
                          }
                        } catch (IllegalArgumentException reserved) {
                          /* Older owner is still cleaning up. */
                        } catch (ClientException failure) {
                          allowed(failure);
                        }
                        continue;
                      }
                      try {
                        int action = random.nextInt(100);
                        if (action < 30) {
                          CompletableFuture<Long> result =
                              client.executeAsync(
                                  Operation.read("read", BUDGET),
                                  (nativeClient, context) -> {
                                    assertEquals(
                                        nativeClient.revision,
                                        context.settings(Long.class).longValue());
                                    return CompletableFuture.supplyAsync(
                                        nativeClient::read, nativeWork);
                                  });
                          if (random.nextInt(8) == 0) result.cancel(false);
                          try {
                            result.get(10, TimeUnit.SECONDS);
                            calls.incrementAndGet();
                          } catch (CancellationException cancelled) {
                            /* Native ownership must still drain. */
                          } catch (ExecutionException failure) {
                            if (failure.getCause() instanceof ClientException sdk) allowed(sdk);
                            else
                              throw new AssertionError(
                                  "Native ownership oracle failed", failure.getCause());
                          }
                        } else if (action < 47) {
                          try (ClientSession<NativeResource> lease = client.session()) {
                            assertEquals(lease.revision(), lease.settings(Long.class).longValue());
                            assertEquals(lease.revision(), lease.client().read());
                            // Explicit and automatic close must release ownership only once.
                            lease.close();
                          }
                        } else if (action < 68) {
                          long next = revisions.incrementAndGet(slot);
                          client.reload(config(slot, next));
                          updates.incrementAndGet();
                        } else if (action < 78) {
                          long attempted = client.status().attemptedRevision();
                          assertEquals(UpdateResult.STALE, client.reload(config(slot, attempted)));
                        } else if (action < 84) {
                          client.revoke();
                          assertThrows(ClientException.class, client::session);
                        } else if (action < 93) {
                          if (slots.compareAndSet(slot, client, null)) {
                            client.close();
                            client.close();
                          }
                        } else {
                          // An old handle's delayed cleanup must not unregister its replacement.
                          ManagedClient<NativeResource, Long> old = handles.peek();
                          if (old != null && old != client && old.status().closed()) old.close();
                        }
                        synchronized (lastObserved) {
                          long current = client.status().revision();
                          long previous = lastObserved.getOrDefault(client, 0L);
                          assertTrue(
                              current >= previous, "A stale publication replaced a newer revision");
                          lastObserved.put(client, current);
                        }
                      } catch (ClientException failure) {
                        allowed(failure);
                      }
                    }
                  } catch (Exception failure) {
                    throw new CompletionException(failure);
                  }
                }));
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      for (Future<?> worker : futures) worker.get(45, TimeUnit.SECONDS);
      assertTrue(calls.get() > 0, "Schedule did not execute any successful calls");
      assertTrue(updates.get() > 0, "Schedule did not exercise any updates");
    } finally {
      callers.shutdownNow();
      callers.awaitTermination(10, TimeUnit.SECONDS);
      for (ManagedClient<NativeResource, Long> handle : handles) handle.close();
      runtime.close(BUDGET);
      nativeWork.shutdown();
      if (!nativeWork.awaitTermination(10, TimeUnit.SECONDS)) nativeWork.shutdownNow();
    }
    eventually(
        () -> runtime.clientCount() == 0 && runtime.isolatedThreadReservations() == 0,
        "Registry capacity or isolated worker reservations leaked");
    for (ManagedClient<NativeResource, Long> handle : handles) {
      assertTrue(handle.status().closed());
      assertEquals(0, handle.status().inFlight());
      assertEquals(0, handle.status().ownedResources());
      assertEquals(0, handle.cleanupFailures());
      assertThrows(ClientException.class, handle::session);
    }
    for (NativeResource nativeClient : oracle.created) {
      assertEquals(1, nativeClient.closes.get(), "Native generation was leaked or closed twice");
      synchronized (nativeClient) {
        assertTrue(nativeClient.closed);
        assertEquals(0, nativeClient.users);
      }
    }
  }
}
