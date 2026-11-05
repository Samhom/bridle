package io.github.samhom.bridle.circuitbreaker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.samhom.bridle.circuitbreaker.event.CircuitBreakerEvent;
import io.github.samhom.bridle.test.ManualClock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
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

  private static CircuitBreaker breaker(int failures, Duration openFor, ManualClock clock) {
    return new CircuitBreaker("fixture", CircuitBreakerConfig.of(failures, openFor), clock);
  }
}
