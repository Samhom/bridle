package io.github.samhom.bridle.circuitbreaker;

import io.github.samhom.bridle.circuitbreaker.event.CircuitBreakerEvent;
import io.github.samhom.bridle.circuitbreaker.internal.CircuitBreakerStateMachine;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Named breaker. The sliding window and epoch live in the internal state machine. */
public final class CircuitBreaker {
  public enum Result {
    SUCCESS,
    FAILURE,
    IGNORED
  }

  private static final CircuitStatus DISABLED_STATUS =
      new CircuitStatus(CircuitStatus.State.DISABLED, 0, 0);
  private static final CircuitBreaker DISABLED =
      new CircuitBreaker("disabled", CircuitBreakerConfig.disabled());

  private final String name;
  private final CircuitBreakerConfig config;
  private final LongSupplier clock;
  private final CircuitBreakerStateMachine machine;
  private final Permit inactive;
  private final CopyOnWriteArrayList<Consumer<CircuitBreakerEvent>> listeners =
      new CopyOnWriteArrayList<>();

  public CircuitBreaker(String name, CircuitBreakerConfig config) {
    this(name, config, System::nanoTime);
  }

  /** Shared breaker for a disabled policy. It keeps no window and does not enter the state lock. */
  public static CircuitBreaker disabled() {
    return DISABLED;
  }

  CircuitBreaker(String name, CircuitBreakerConfig config, LongSupplier clock) {
    this.name = Objects.requireNonNull(name, "name");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.machine = new CircuitBreakerStateMachine(config, clock, this::emit);
    this.inactive = config.failureThreshold() == 0 ? new Permit(null) : null;
  }

  public String name() {
    return name;
  }

  public CircuitBreakerConfig config() {
    return config;
  }

  public Permit acquire() {
    if (inactive != null) return inactive;
    return new Permit(machine.acquire());
  }

  public CircuitStatus status() {
    if (inactive != null) return DISABLED_STATUS;
    return machine.status();
  }

  public void onEvent(Consumer<CircuitBreakerEvent> listener) {
    listeners.add(Objects.requireNonNull(listener, "listener"));
  }

  private void emit(CircuitBreakerEvent.Type type) {
    if (listeners.isEmpty()) return;
    CircuitBreakerEvent event = new CircuitBreakerEvent(name, type, clock.getAsLong());
    for (Consumer<CircuitBreakerEvent> listener : listeners) {
      try {
        listener.accept(event);
      } catch (RuntimeException ignored) {
      }
    }
  }

  public final class Permit {
    private final CircuitBreakerStateMachine.Permit delegate;

    Permit(CircuitBreakerStateMachine.Permit delegate) {
      this.delegate = delegate;
    }

    public void abandon() {
      if (delegate != null) delegate.abandon();
    }

    public void complete(Result result) {
      Objects.requireNonNull(result, "result");
      if (delegate != null) delegate.complete(result);
    }
  }
}
