package io.github.samhom.driverkit.core;

import java.time.Duration;

/** A single deadline is shared with the adapter and all native attempts. */
public final class CallContext {
  private long deadline;
  private final long started;
  private Object settings;
  private final ContextPropagation.Snapshot snapshot;

  CallContext(Duration timeout) {
    this(timeout, CallContexts.capture());
  }

  CallContext(Duration timeout, ContextPropagation.Snapshot snapshot) {
    this.snapshot = java.util.Objects.requireNonNull(snapshot);
    started = System.nanoTime();
    deadline = started + timeout.toNanos();
  }

  void limitTo(Duration timeout) {
    java.util.Objects.requireNonNull(timeout);
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0)
      throw new IllegalArgumentException("Invalid operation ceiling");
    deadline = Math.min(deadline, started + timeout.toNanos());
  }

  public ContextPropagation.Scope openScope() {
    return snapshot.open();
  }

  public Runnable wrap(Runnable callback) {
    return () -> snapshot.run(callback);
  }

  public <T, U> java.util.function.BiConsumer<T, U> wrap(
      java.util.function.BiConsumer<T, U> callback) {
    return (value, error) -> snapshot.run(() -> callback.accept(value, error));
  }

  public <T, R> java.util.function.Function<T, R> wrap(java.util.function.Function<T, R> callback) {
    return value -> {
      try (ContextPropagation.Scope ignored = snapshot.open()) {
        return callback.apply(value);
      }
    };
  }

  void bindSettings(Object settings) {
    this.settings = settings;
  }

  public <S> S settings(Class<S> type) {
    return type.cast(settings);
  }

  public Duration remaining() {
    return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
  }

  /**
   * Positive milliseconds for native APIs that cannot express a shorter unit. The result is never
   * rounded up: a sub-millisecond remainder cannot be handed to a socket as one extra millisecond,
   * and zero is never converted into an infinite native timeout.
   */
  public int remainingMillis(Duration ceiling) {
    checkDeadline();
    long nanos = Math.min(remaining().toNanos(), Limits.timeout(ceiling).toNanos());
    return millisWithin(nanos);
  }

  /** Floor of a remaining nanosecond budget. Exposed so other modules share one conversion. */
  public static int millisWithin(long remainingNanos) {
    if (remainingNanos < 1_000_000L)
      throw new ClientException(ClientException.Kind.TIMEOUT, "Operation deadline exceeded");
    long millis = remainingNanos / 1_000_000L;
    return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
  }

  public void checkDeadline() {
    if (remaining().isZero())
      throw new ClientException(ClientException.Kind.TIMEOUT, "Operation deadline exceeded");
  }
}
