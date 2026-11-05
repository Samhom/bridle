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

  private final String name;
  private final CircuitBreakerConfig config;
  private final LongSupplier clock;
  private final CircuitBreakerStateMachine machine;
  private final CopyOnWriteArrayList<Consumer<CircuitBreakerEvent>> listeners =
      new CopyOnWriteArrayList<>();

  public CircuitBreaker(String name, CircuitBreakerConfig config) {
    this(name, config, System::nanoTime);
  }

  CircuitBreaker(String name, CircuitBreakerConfig config, LongSupplier clock) {
    this.name = Objects.requireNonNull(name, "name");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.machine = new CircuitBreakerStateMachine(config, clock, this::emit);
  }

  public String name() {
    return name;
  }

  public CircuitBreakerConfig config() {
    return config;
  }

  public Permit acquire() {
    return new Permit(machine.acquire());
  }

  public CircuitStatus status() {
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
      delegate.abandon();
    }

    public void complete(Result result) {
      delegate.complete(Objects.requireNonNull(result, "result"));
    }
  }
}
