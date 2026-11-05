package io.github.samhom.bridle.core;

public interface Observation {
  void finish(Outcome outcome, long elapsedNanos);

  default void queued(long nanos) {}

  default AutoCloseable openScope() {
    return () -> {};
  }

  enum Outcome {
    SUCCESS,
    ERROR,
    CANCELLED,
    TIMEOUT,
    REJECTED
  }

  Observation NOOP = (outcome, elapsedNanos) -> {};
}
