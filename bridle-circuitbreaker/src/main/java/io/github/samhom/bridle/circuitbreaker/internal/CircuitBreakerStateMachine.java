package io.github.samhom.bridle.circuitbreaker.internal;

import io.github.samhom.bridle.circuitbreaker.CallNotPermittedException;
import io.github.samhom.bridle.circuitbreaker.CircuitBreaker;
import io.github.samhom.bridle.circuitbreaker.CircuitBreakerConfig;
import io.github.samhom.bridle.circuitbreaker.CircuitStatus;
import io.github.samhom.bridle.circuitbreaker.event.CircuitBreakerEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Count-based circuit. Epochs drop results from a previous state. A slow success is a recorded
 * failure only when a slow-call threshold is configured.
 */
public final class CircuitBreakerStateMachine {
  private static final byte FAILURE = 2;

  private final CircuitBreakerConfig config;
  private final LongSupplier clock;
  private final Consumer<CircuitBreakerEvent.Type> events;
  private final byte[] window;
  private CircuitStatus.State state = CircuitStatus.State.CLOSED;
  private int consecutive, windowCursor, windowSize, windowFailures;
  private int epoch, inFlight, halfFinished, halfFailures;
  private long until;

  public CircuitBreakerStateMachine(
      CircuitBreakerConfig config, LongSupplier clock, Consumer<CircuitBreakerEvent.Type> events) {
    this.config = config;
    this.clock = clock;
    this.events = events;
    this.window = new byte[config.windowSize()];
  }

  public synchronized Permit acquire() {
    if (config.failureThreshold() == 0) return new Permit(epoch, false);
    if (state == CircuitStatus.State.OPEN) {
      if (clock.getAsLong() - until < 0)
        throw new CallNotPermittedException("Resource circuit is open");
      state = CircuitStatus.State.HALF_OPEN;
      epoch++;
      inFlight = 0;
      halfFinished = 0;
      halfFailures = 0;
      events.accept(CircuitBreakerEvent.Type.HALF_OPEN);
    }
    if (state == CircuitStatus.State.HALF_OPEN && inFlight + halfFinished >= config.halfOpenCalls())
      throw new CallNotPermittedException("Circuit recovery sample is still in flight");
    if (state == CircuitStatus.State.HALF_OPEN) inFlight++;
    return new Permit(epoch, state == CircuitStatus.State.HALF_OPEN);
  }

  public synchronized CircuitStatus status() {
    return new CircuitStatus(
        config.failureThreshold() == 0 ? CircuitStatus.State.DISABLED : state,
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
    if (consecutive >= config.failureThreshold() || rateExceeded(windowFailures, windowSize))
      open();
  }

  private boolean rateExceeded(int failures, int calls) {
    return calls == window.length && failures * 100 >= calls * config.failureRatePercent();
  }

  private void open() {
    state = CircuitStatus.State.OPEN;
    until = clock.getAsLong() + config.openFor().toNanos();
    inFlight = 0;
    halfFinished = 0;
    halfFailures = 0;
    windowCursor = 0;
    windowSize = 0;
    windowFailures = 0;
    epoch++;
    events.accept(CircuitBreakerEvent.Type.OPENED);
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
    events.accept(CircuitBreakerEvent.Type.CLOSED);
  }

  public final class Permit {
    final long generation;
    final boolean probe;
    final long started;
    final AtomicBoolean finished = new AtomicBoolean();

    Permit(long generation, boolean probe) {
      this.generation = generation;
      this.probe = probe;
      this.started = clock.getAsLong();
    }

    public void abandon() {
      if (!finished.compareAndSet(false, true)) return;
      synchronized (CircuitBreakerStateMachine.this) {
        if (generation == epoch && probe && inFlight > 0) inFlight--;
      }
    }

    public void complete(CircuitBreaker.Result result) {
      if (!finished.compareAndSet(false, true)) return;
      synchronized (CircuitBreakerStateMachine.this) {
        if (config.failureThreshold() == 0 || generation != epoch) return;
        boolean slow =
            result == CircuitBreaker.Result.SUCCESS
                && config.slowCallThreshold().toNanos() > 0
                && clock.getAsLong() - started >= config.slowCallThreshold().toNanos();
        if (result == CircuitBreaker.Result.SUCCESS && !slow) {
          if (probe) finishProbe(false);
          else record(false);
          return;
        }
        if (result == CircuitBreaker.Result.IGNORED) {
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
      if (halfFinished < config.halfOpenCalls() || inFlight > 0) return;
      if (halfFailures * 100 >= halfFinished * config.failureRatePercent()) open();
      else close();
    }
  }
}
