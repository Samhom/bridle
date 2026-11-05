package io.github.samhom.bridle.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Count-based circuit in the Resilience4j shape. Epochs drop results from a previous state. A slow
 * success is a recorded failure only when a slow-call threshold is configured.
 */
final class CircuitBreaker {
  private static final byte FAILURE = 2;

  private final ExecutionPolicy policy;
  private final LongSupplier clock;
  private final byte[] window;
  private CircuitStatus.State state = CircuitStatus.State.CLOSED;
  private int consecutive, windowCursor, windowSize, windowFailures;
  private int epoch, inFlight, halfFinished, halfFailures;
  private long until;

  CircuitBreaker(ExecutionPolicy policy) {
    this(policy, System::nanoTime);
  }

  CircuitBreaker(ExecutionPolicy policy, LongSupplier clock) {
    this.policy = policy;
    this.clock = clock;
    this.window = new byte[policy.windowSize()];
  }

  synchronized Permit acquire() {
    if (policy.failureThreshold() == 0) return new Permit(epoch, false);
    if (state == CircuitStatus.State.OPEN) {
      if (clock.getAsLong() - until < 0)
        throw new ClientException(ClientException.Kind.CIRCUIT_OPEN, "Resource circuit is open");
      state = CircuitStatus.State.HALF_OPEN;
      epoch++;
      inFlight = 0;
      halfFinished = 0;
      halfFailures = 0;
    }
    if (state == CircuitStatus.State.HALF_OPEN && inFlight + halfFinished >= policy.halfOpenCalls())
      throw new ClientException(
          ClientException.Kind.CIRCUIT_OPEN, "Circuit recovery sample is still in flight");
    if (state == CircuitStatus.State.HALF_OPEN) inFlight++;
    return new Permit(epoch, state == CircuitStatus.State.HALF_OPEN);
  }

  synchronized CircuitStatus status() {
    return new CircuitStatus(
        policy.failureThreshold() == 0 ? CircuitStatus.State.DISABLED : state,
        consecutive,
        state == CircuitStatus.State.OPEN ? Math.max(0, until - clock.getAsLong()) : 0);
  }

  private void record(boolean failure) {
    if (windowSize == window.length) {
      if (window[windowCursor] == FAILURE) windowFailures--;
    } else {
      windowSize++;
    }
    window[windowCursor] = failure ? FAILURE : 1;
    windowCursor = (windowCursor + 1) % window.length;
    if (failure) {
      windowFailures++;
      consecutive++;
    } else {
      consecutive = 0;
    }
    if (consecutive >= policy.failureThreshold() || rateExceeded(windowFailures, windowSize))
      open();
  }

  private boolean rateExceeded(int failures, int calls) {
    return calls == window.length && failures * 100 >= calls * policy.failureRatePercent();
  }

  private void open() {
    state = CircuitStatus.State.OPEN;
    until = clock.getAsLong() + policy.openFor().toNanos();
    inFlight = 0;
    halfFinished = 0;
    halfFailures = 0;
    windowCursor = 0;
    windowSize = 0;
    windowFailures = 0;
    epoch++;
  }

  private void close() {
    state = CircuitStatus.State.CLOSED;
    consecutive = 0;
    inFlight = 0;
    halfFinished = 0;
    halfFailures = 0;
    windowCursor = 0;
    windowSize = 0;
    windowFailures = 0;
    epoch++;
  }

  final class Permit {
    final long generation;
    final boolean probe;
    final long started;
    final AtomicBoolean finished = new AtomicBoolean();

    Permit(long generation, boolean probe) {
      this.generation = generation;
      this.probe = probe;
      this.started = clock.getAsLong();
    }

    void abandon() {
      if (!finished.compareAndSet(false, true)) return;
      synchronized (CircuitBreaker.this) {
        if (generation == epoch && probe && inFlight > 0) inFlight--;
      }
    }

    void complete(Throwable error) {
      if (!finished.compareAndSet(false, true)) return;
      synchronized (CircuitBreaker.this) {
        if (policy.failureThreshold() == 0 || generation != epoch) return;
        boolean slow =
            error == null
                && policy.slowCallNanos() > 0
                && clock.getAsLong() - started >= policy.slowCallNanos();
        if (error == null && !slow) {
          if (probe) finishProbe(false);
          else record(false);
          return;
        }
        ClientException.Kind kind =
            error instanceof ClientException
                ? ((ClientException) error).kind()
                : ClientException.Kind.DRIVER_FAILURE;
        // An ambiguous write is not evidence the dependency is down. Opening the circuit on it
        // would shed later reads after a mutation that may already have committed.
        boolean transientFailure =
            slow
                || kind == ClientException.Kind.TIMEOUT
                || kind == ClientException.Kind.UNAVAILABLE
                || kind == ClientException.Kind.CONNECTION_FAILURE
                || kind == ClientException.Kind.DRIVER_FAILURE
                || kind == ClientException.Kind.RATE_LIMITED
                || kind == ClientException.Kind.SERVER_REJECTED;
        if (!transientFailure) {
          if (probe && inFlight > 0) inFlight--;
          return;
        }
        if (probe) finishProbe(true);
        else record(true);
      }
    }

    private void finishProbe(boolean failure) {
      if (inFlight > 0) inFlight--;
      halfFinished++;
      if (failure) halfFailures++;
      if (halfFinished < policy.halfOpenCalls() || inFlight > 0) return;
      if (halfFailures * 100 >= halfFinished * policy.failureRatePercent()) open();
      else close();
    }
  }
}
