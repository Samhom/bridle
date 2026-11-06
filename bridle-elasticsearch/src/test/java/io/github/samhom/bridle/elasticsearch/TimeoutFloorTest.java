package io.github.samhom.bridle.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.samhom.bridle.core.ClientException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TimeoutFloorTest {
  @Test
  void warmupAndPitBudgetsFloorInsteadOfRoundingUp() {
    assertEquals(1, ElasticsearchAdapter.readinessMillis(Duration.ofNanos(1_500_000)));
    assertEquals(2, ElasticsearchAdapter.readinessMillis(Duration.ofNanos(2_000_000)));
    assertEquals(1, ManagedSearch.stageMillis(Duration.ofNanos(1_500_000), Duration.ofMillis(50)));
    assertEquals(2, ManagedSearch.stageMillis(Duration.ofMillis(50), Duration.ofNanos(2_000_000)));
    assertEquals(
        ClientException.Kind.TIMEOUT,
        assertThrows(
                ClientException.class,
                () -> ElasticsearchAdapter.readinessMillis(Duration.ofNanos(999_999)))
            .kind());
    assertEquals(
        ClientException.Kind.TIMEOUT,
        assertThrows(
                ClientException.class,
                () -> ManagedSearch.stageMillis(Duration.ofNanos(999_999), Duration.ofMillis(50)))
            .kind());
  }
}
