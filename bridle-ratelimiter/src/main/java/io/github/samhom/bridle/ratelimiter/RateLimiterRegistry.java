package io.github.samhom.bridle.ratelimiter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Named token buckets. The first configuration for a name is kept. */
public final class RateLimiterRegistry {
  private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();

  public RateLimiter rateLimiter(String name, RateLimiterConfig config) {
    if (name == null || name.isEmpty() || name.length() > 300)
      throw new IllegalArgumentException("Invalid rate limiter name");
    return limiters.computeIfAbsent(
        name, key -> new RateLimiter(key, Objects.requireNonNull(config, "config")));
  }

  public RateLimiter find(String name) {
    return limiters.get(name);
  }

  public Set<String> names() {
    return Collections.unmodifiableSet(new HashSet<String>(limiters.keySet()));
  }
}
