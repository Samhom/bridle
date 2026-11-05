package io.github.samhom.bridle.circuitbreaker.event;

/** One observed state transition. Listeners run on the caller that changed the breaker. */
public final class CircuitBreakerEvent {
  public enum Type {
    OPENED,
    HALF_OPEN,
    CLOSED
  }

  private final String name;
  private final Type type;
  private final long createdAtNanos;

  public CircuitBreakerEvent(String name, Type type, long createdAtNanos) {
    this.name = name;
    this.type = type;
    this.createdAtNanos = createdAtNanos;
  }

  public String name() {
    return name;
  }

  public Type type() {
    return type;
  }

  public long createdAtNanos() {
    return createdAtNanos;
  }
}
