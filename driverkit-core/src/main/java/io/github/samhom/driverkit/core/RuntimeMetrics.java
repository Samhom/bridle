package io.github.samhom.driverkit.core;

/**
 * Point-in-time resource metrics. Queue and worker counts describe the resource's execution lane;
 * shared lanes intentionally report shared pressure. No native pool internals are inferred.
 */
public final class RuntimeMetrics {
  public final int inFlight, queuedCalls, activeWorkers, queueCapacity, cleanupFailures;
  public final long rejectedUpdates, retiringAgeNanos;

  RuntimeMetrics(
      int inFlight,
      int queuedCalls,
      int activeWorkers,
      int queueCapacity,
      int cleanupFailures,
      long rejectedUpdates,
      long retiringAgeNanos) {
    this.inFlight = inFlight;
    this.queuedCalls = queuedCalls;
    this.activeWorkers = activeWorkers;
    this.queueCapacity = queueCapacity;
    this.cleanupFailures = cleanupFailures;
    this.rejectedUpdates = rejectedUpdates;
    this.retiringAgeNanos = retiringAgeNanos;
  }
}
