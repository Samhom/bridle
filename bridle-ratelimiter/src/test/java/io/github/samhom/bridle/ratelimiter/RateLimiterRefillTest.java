package io.github.samhom.bridle.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RateLimiterRefillTest {
  @Test
  void partialSecondDoesNotMintATokenEarly() {
    long[] now = {0};
    RateLimiter limiter = new RateLimiter("writes", new RateLimiterConfig(1, 1), () -> now[0]);
    assertTrue(limiter.acquire());
    assertFalse(limiter.acquire());
    now[0] += 999_000_000L;
    assertFalse(limiter.acquire());
    now[0] += 1_000_000L;
    assertTrue(limiter.acquire());
    assertFalse(limiter.acquire());
  }

  @Test
  void fractionalStepsAddWholeTokensWithoutOverflow() {
    long[] now = {0};
    RateLimiter limiter = new RateLimiter("writes", new RateLimiterConfig(10, 1), () -> now[0]);
    assertTrue(limiter.acquire());
    for (int step = 0; step < 10; step++) {
      now[0] += 100_000_000L;
      assertTrue(limiter.acquire());
      assertFalse(limiter.acquire());
    }
    now[0] = Long.MAX_VALUE / 2;
    assertTrue(limiter.acquire());
    assertFalse(limiter.acquire());
  }
}
