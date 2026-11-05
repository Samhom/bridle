package io.github.samhom.bridle.circuitbreaker;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Named breakers owned by one runtime. A later registration with the same name replaces lookup. */
public final class CircuitBreakerRegistry {
  private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

  public CircuitBreaker circuitBreaker(String name, CircuitBreakerConfig config) {
    if (name == null || name.isEmpty() || name.length() > 300)
      throw new IllegalArgumentException("Invalid circuit breaker name");
    CircuitBreaker created = new CircuitBreaker(name, Objects.requireNonNull(config, "config"));
    breakers.put(name, created);
    return created;
  }

  public CircuitBreaker find(String name) {
    return breakers.get(name);
  }

  public Set<String> names() {
    return Collections.unmodifiableSet(new HashSet<String>(breakers.keySet()));
  }
}
