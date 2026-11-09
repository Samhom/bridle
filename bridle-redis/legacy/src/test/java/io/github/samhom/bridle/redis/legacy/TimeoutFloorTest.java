package io.github.samhom.bridle.redis.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.samhom.bridle.core.ClientException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TimeoutFloorTest {
  @Test
  void warmupBudgetFloorsInsteadOfStretchingASubMillisecondRemainder() {
    assertEquals(1, Jedis3Adapter.readinessMillis(Duration.ofNanos(1_500_000)));
    assertEquals(
        ClientException.Kind.TIMEOUT,
        assertThrows(
                ClientException.class,
                () -> Jedis3Adapter.readinessMillis(Duration.ofNanos(999_999)))
            .kind());
  }
}
