package io.github.samhom.bridle.ratelimiter;

import java.util.Objects;
import java.util.function.LongSupplier;

/** One token bucket. Permits are local and are not coordinated across processes. */
public final class RateLimiter {
  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private final String name;
  private final RateLimiterConfig config;
  private final LongSupplier clock;
  private long last;
  private long tokens;
  private long residual;

  public RateLimiter(String name, RateLimiterConfig config) {
    this(name, config, System::nanoTime);
  }

  RateLimiter(String name, RateLimiterConfig config, LongSupplier clock) {
    this.name = Objects.requireNonNull(name, "name");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.tokens = config.burst();
    this.last = this.clock.getAsLong();
  }

  public String name() {
    return name;
  }

  public RateLimiterConfig config() {
    return config;
  }

  public synchronized boolean acquire() {
    long now = clock.getAsLong();
    refill(Math.max(0, now - last));
    last = now;
    if (tokens < 1) return false;
    tokens -= 1;
    return true;
  }

  private void refill(long elapsed) {
    int rate = config.perSecond();
    if (elapsed == 0) return;
    long cap = config.burst();
    if (elapsed > (Long.MAX_VALUE - residual) / rate) {
      tokens = cap;
      residual = 0;
      return;
    }
    long credit = elapsed * rate + residual;
    long added = credit / NANOS_PER_SECOND;
    residual = credit % NANOS_PER_SECOND;
    if (tokens >= cap || added >= cap - tokens) tokens = cap;
    else tokens += added;
  }
}
