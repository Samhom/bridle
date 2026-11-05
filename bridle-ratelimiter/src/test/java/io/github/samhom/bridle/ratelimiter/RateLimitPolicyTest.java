package io.github.samhom.bridle.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.samhom.bridle.core.ClientException;
import io.github.samhom.bridle.core.Operation;
import io.github.samhom.bridle.core.ResourceId;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RateLimitPolicyTest {
  private static final ResourceId RESOURCE = new ResourceId("example", "test", "events");

  @Test
  void localRatePolicyRejectsAfterConfiguredBurst() {
    RateLimitPolicy limiter = new RateLimitPolicy(Map.of(RESOURCE, new RateLimiterConfig(1, 1)));
    limiter.check(RESOURCE, Operation.read("get", Duration.ofSeconds(1)));
    assertEquals(
        ClientException.Kind.RATE_LIMITED,
        assertThrows(
                ClientException.class,
                () -> limiter.check(RESOURCE, Operation.read("get", Duration.ofSeconds(1))))
            .kind());
  }

  @Test
  void registryKeepsTheFirstLimiterForAName() {
    RateLimiterRegistry registry = new RateLimiterRegistry();
    RateLimiter first = registry.rateLimiter("writes", new RateLimiterConfig(1, 1));
    RateLimiter second = registry.rateLimiter("writes", new RateLimiterConfig(5, 5));
    assertEquals(first, second);
    assertEquals(1, first.config().perSecond());
  }
}
