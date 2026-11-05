package io.github.samhom.bridle.core;

import java.time.Duration;

/**
 * Immutable opt-in resource isolation and circuit policy. The circuit follows Resilience4j's
 * count-based window: a full window opens at a 50% recorded-failure rate, and half-open traffic
 * stays capped until that sample is decided.
 */
public final class ExecutionPolicy {
  static final int FAILURE_RATE_PERCENT = 50;
  private static final int HALF_OPEN_CAP = 8;

  private final int threads, queue, failures;
  private final Duration openFor;
  private final long slowCallNanos;

  private ExecutionPolicy(
      int threads, int queue, int failures, Duration openFor, long slowCallNanos) {
    this.threads = threads;
    this.queue = queue;
    this.failures = failures;
    this.openFor = openFor;
    this.slowCallNanos = slowCallNanos;
  }

  public static ExecutionPolicy shared() {
    return new ExecutionPolicy(0, 0, 0, Duration.ofSeconds(1), 0);
  }

  public static ExecutionPolicy isolated(int threads, int queue) {
    return new ExecutionPolicy(
        Limits.count(threads, 64), Limits.count(queue, 10000), 0, Duration.ofSeconds(1), 0);
  }

  public ExecutionPolicy withCircuitBreaker(int failures, Duration openFor) {
    return new ExecutionPolicy(
        threads, queue, Limits.count(failures, 100000), Limits.timeout(openFor), slowCallNanos);
  }

  /** Successful calls at least this slow are recorded as failures. The window still decides. */
  public ExecutionPolicy withSlowCallThreshold(Duration slowCall) {
    return new ExecutionPolicy(
        threads, queue, failures, openFor, Limits.timeout(slowCall).toNanos());
  }

  public int threads() {
    return threads;
  }

  public int queuedCalls() {
    return queue;
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

  long slowCallNanos() {
    return slowCallNanos;
  }
}
