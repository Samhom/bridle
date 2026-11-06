package io.github.samhom.bridle.circuitbreaker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samhom.bridle.circuitbreaker.event.CircuitBreakerEvent;
import io.github.samhom.bridle.test.ManualClock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {
  static final Duration SECOND = Duration.ofSeconds(1);

  @Test
  void halfOpenHasOneProbeAndIgnoresLateResultsFromAnOldEpoch() {
    ManualClock time = new ManualClock();
    CircuitBreaker breaker = breaker(1, SECOND, time);
    CircuitBreaker.Permit late = breaker.acquire();
    CircuitBreaker.Permit failure = breaker.acquire();
    failure.complete(CircuitBreaker.Result.FAILURE);
    assertThrows(CallNotPermittedException.class, breaker::acquire);
    time.advance(SECOND);
    CircuitBreaker.Permit probe = breaker.acquire();
    assertThrows(CallNotPermittedException.class, breaker::acquire);
    probe.abandon();
    probe = breaker.acquire();
    probe.complete(CircuitBreaker.Result.SUCCESS);
    late.complete(CircuitBreaker.Result.FAILURE);
    assertEquals(CircuitStatus.State.CLOSED, breaker.status().state());
  }

  @Test
  void ignoredResultDoesNotOpenTheCircuit() {
    CircuitBreaker breaker = breaker(1, SECOND, new ManualClock());
    breaker.acquire().complete(CircuitBreaker.Result.IGNORED);
    assertEquals(CircuitStatus.State.CLOSED, breaker.status().state());
    assertDoesNotThrow(breaker::acquire);
  }

  @Test
  void alternatingOutcomesOpenWhenTheWindowIsHalfFailed() {
    CircuitBreaker breaker = breaker(2, SECOND, new ManualClock());
    breaker.acquire().complete(CircuitBreaker.Result.FAILURE);
    breaker.acquire().complete(CircuitBreaker.Result.SUCCESS);
    breaker.acquire().complete(CircuitBreaker.Result.FAILURE);
    assertEquals(CircuitStatus.State.CLOSED, breaker.status().state());
    breaker.acquire().complete(CircuitBreaker.Result.SUCCESS);
    assertEquals(CircuitStatus.State.OPEN, breaker.status().state());
  }

  @Test
  void halfOpenSampleStaysCappedUntilEveryProbeIsDecided() {
    ManualClock time = new ManualClock();
    CircuitBreaker breaker = breaker(2, SECOND, time);
    breaker.acquire().complete(CircuitBreaker.Result.FAILURE);
    breaker.acquire().complete(CircuitBreaker.Result.FAILURE);
    assertEquals(CircuitStatus.State.OPEN, breaker.status().state());
    time.advance(SECOND);
    CircuitBreaker.Permit first = breaker.acquire();
    CircuitBreaker.Permit second = breaker.acquire();
    assertThrows(CallNotPermittedException.class, breaker::acquire);
    first.complete(CircuitBreaker.Result.SUCCESS);
    assertEquals(CircuitStatus.State.HALF_OPEN, breaker.status().state());
    second.complete(CircuitBreaker.Result.FAILURE);
    assertEquals(CircuitStatus.State.OPEN, breaker.status().state());
  }

  @Test
  void slowSuccessIsARecordedFailure() {
    ManualClock time = new ManualClock();
    CircuitBreaker breaker =
        new CircuitBreaker(
            "slow",
            CircuitBreakerConfig.of(2, SECOND).withSlowCallThreshold(Duration.ofMillis(50)),
            time);
    CircuitBreaker.Permit first = breaker.acquire();
    time.advance(Duration.ofMillis(50));
    first.complete(CircuitBreaker.Result.SUCCESS);
    assertEquals(CircuitStatus.State.CLOSED, breaker.status().state());
    CircuitBreaker.Permit second = breaker.acquire();
    time.advance(Duration.ofMillis(50));
    second.complete(CircuitBreaker.Result.SUCCESS);
    assertEquals(CircuitStatus.State.OPEN, breaker.status().state());
  }

  @Test
  void registryReplacesLookupAndPublishesOpen() {
    CircuitBreakerRegistry registry = new CircuitBreakerRegistry();
    AtomicInteger opened = new AtomicInteger();
    CircuitBreaker breaker =
        registry.circuitBreaker("app/test/cache", CircuitBreakerConfig.of(1, SECOND));
    breaker.onEvent(
        event -> {
          if (event.type() == CircuitBreakerEvent.Type.OPENED) opened.incrementAndGet();
        });
    breaker.acquire().complete(CircuitBreaker.Result.FAILURE);
    assertEquals(1, opened.get());
    assertEquals(breaker, registry.find("app/test/cache"));
  }

  @Test
  void removeDropsOnlyTheBreakerThatIsLeaving() {
    CircuitBreakerRegistry registry = new CircuitBreakerRegistry();
    CircuitBreaker first =
        registry.circuitBreaker("app/test/cache", CircuitBreakerConfig.of(1, SECOND));
    CircuitBreaker replacement =
        registry.circuitBreaker("app/test/cache", CircuitBreakerConfig.of(2, SECOND));
    assertFalse(registry.remove("app/test/cache", first));
    assertEquals(replacement, registry.find("app/test/cache"));
    assertTrue(registry.remove("app/test/cache", replacement));
    assertNull(registry.find("app/test/cache"));
  }

  @Test
  void listenerReadsStatusWithoutWaitingForTheStateLock() throws Exception {
    CircuitBreaker breaker = breaker(1, SECOND, new ManualClock());
    CountDownLatch publishing = new CountDownLatch(1);
    CountDownLatch statusRead = new CountDownLatch(1);
    AtomicReference<CircuitStatus.State> seen = new AtomicReference<>();
    AtomicLong blockedNanos = new AtomicLong();
    breaker.onEvent(
        event -> {
          if (event.type() != CircuitBreakerEvent.Type.OPENED) return;
          publishing.countDown();
          long started = System.nanoTime();
          try {
            statusRead.await(2, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          blockedNanos.set(System.nanoTime() - started);
        });
    Thread reader =
        new Thread(
            () -> {
              try {
                if (!publishing.await(2, TimeUnit.SECONDS)) return;
                seen.set(breaker.status().state());
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              } finally {
                statusRead.countDown();
              }
            });
    reader.start();
    breaker.acquire().complete(CircuitBreaker.Result.FAILURE);
    reader.join(2_000);
    assertFalse(reader.isAlive());
    assertEquals(CircuitStatus.State.OPEN, seen.get());
    assertTrue(blockedNanos.get() < 1_500_000_000L);
  }

  private static CircuitBreaker breaker(int failures, Duration openFor, ManualClock clock) {
    return new CircuitBreaker("fixture", CircuitBreakerConfig.of(failures, openFor), clock);
  }
}
