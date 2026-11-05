package io.github.samhom.bridle.circuitbreaker;

import java.time.Duration;
import java.util.Objects;

/**
 * Count-based window settings. A full window opens at a 50% recorded-failure rate, and half-open
 * traffic stays capped until that sample is decided.
 */
public final class CircuitBreakerConfig {
  static final int FAILURE_RATE_PERCENT = 50;
  private static final int HALF_OPEN_CAP = 8;

  private final int failures;
  private final Duration openFor;
  private final long slowCallNanos;

  private CircuitBreakerConfig(int failures, Duration openFor, long slowCallNanos) {
    this.failures = failures;
    this.openFor = openFor;
    this.slowCallNanos = slowCallNanos;
  }

  public static CircuitBreakerConfig disabled() {
    return new CircuitBreakerConfig(0, Duration.ofSeconds(1), 0);
  }

  public static CircuitBreakerConfig of(int failures, Duration openFor) {
    return new CircuitBreakerConfig(count(failures, 100000), timeout(openFor), 0);
  }

  /** Successful calls at least this slow are recorded as failures. The window still decides. */
  public CircuitBreakerConfig withSlowCallThreshold(Duration slowCall) {
    return new CircuitBreakerConfig(failures, openFor, timeout(slowCall).toNanos());
  }

  public int failureThreshold() {
    return failures;
  }

  public Duration openFor() {
    return openFor;
  }

  public int windowSize() {
    return failures == 0 ? 0 : failures * 2;
  }

  public int halfOpenCalls() {
    return failures == 0 ? 0 : Math.min(failures, HALF_OPEN_CAP);
  }

  public int failureRatePercent() {
    return FAILURE_RATE_PERCENT;
  }

  public Duration slowCallThreshold() {
    return Duration.ofNanos(slowCallNanos);
  }

  private static Duration timeout(Duration value) {
    Objects.requireNonNull(value, "timeout");
    if (value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(Duration.ofMinutes(10)) > 0)
      throw new IllegalArgumentException("Timeout out of bounds");
    return value;
  }

  private static int count(int value, int maximum) {
    if (value < 1 || value > maximum)
      throw new IllegalArgumentException("Resource count out of bounds");
    return value;
  }
}
