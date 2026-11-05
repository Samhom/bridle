package io.github.samhom.bridle.ratelimiter;

import java.util.Objects;

/** One token bucket. Permits are local and are not coordinated across processes. */
public final class RateLimiter {
  private final String name;
  private final RateLimiterConfig config;
  private long last = System.nanoTime();
  private double tokens;

  public RateLimiter(String name, RateLimiterConfig config) {
    this.name = Objects.requireNonNull(name, "name");
    this.config = Objects.requireNonNull(config, "config");
    this.tokens = config.burst();
  }

  public String name() {
    return name;
  }

  public RateLimiterConfig config() {
    return config;
  }

  public synchronized boolean acquire() {
    long now = System.nanoTime();
    tokens =
        Math.min(
            config.burst(), tokens + Math.max(0, now - last) / 1_000_000_000d * config.perSecond());
    last = now;
    if (tokens < 1) return false;
    tokens -= 1;
    return true;
  }
}
