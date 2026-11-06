package io.github.samhom.bridle.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.samhom.bridle.core.ClientException;
import org.junit.jupiter.api.Test;

class TimeoutFloorTest {
  @Test
  void warmupBudgetFloorsInsteadOfRoundingUp() {
    assertEquals(1, JedisAdapter.readinessMillis(1_500_000L));
    assertEquals(2, JedisAdapter.readinessMillis(2_000_000L));
    assertEquals(
        ClientException.Kind.TIMEOUT,
        assertThrows(ClientException.class, () -> JedisAdapter.readinessMillis(999_999L)).kind());
  }
}
