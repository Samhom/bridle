package io.github.samhom.bridle.ratelimiter;

import io.github.samhom.bridle.core.AdmissionPolicy;
import io.github.samhom.bridle.core.ClientException;
import io.github.samhom.bridle.core.Operation;
import io.github.samhom.bridle.core.ResourceId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Token buckets exist only for statically configured resources, keeping memory bounded. */
public final class RateLimitPolicy implements AdmissionPolicy {
  private final Map<ResourceId, RateLimiter> buckets = new HashMap<>();

  public RateLimitPolicy(Map<ResourceId, RateLimiterConfig> limits) {
    if (limits.size() > 4096) throw new IllegalArgumentException("Too many rate-limited resources");
    limits.forEach(
        (resource, limit) ->
            buckets.put(
                Objects.requireNonNull(resource),
                new RateLimiter(resource.toString(), Objects.requireNonNull(limit))));
  }

  @Override
  public void check(ResourceId resource, Operation operation) {
    RateLimiter bucket = buckets.get(resource);
    if (bucket != null && !bucket.acquire())
      throw new ClientException(ClientException.Kind.RATE_LIMITED, "Resource rate limit exceeded");
  }
}
