package io.github.samhom.bridle.ratelimiter;

import io.github.samhom.bridle.core.Limits;

/** Steady rate and burst for one token bucket. */
public final class RateLimiterConfig {
  private final int perSecond;
  private final int burst;

  public RateLimiterConfig(int perSecond, int burst) {
    this.perSecond = Limits.count(perSecond, 1000000);
    this.burst = Limits.count(burst, 1000000);
  }

  public int perSecond() {
    return perSecond;
  }

  public int burst() {
    return burst;
  }
}
