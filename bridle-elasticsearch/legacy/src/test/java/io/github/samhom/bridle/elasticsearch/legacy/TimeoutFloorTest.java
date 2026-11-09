package io.github.samhom.bridle.elasticsearch.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.samhom.bridle.core.ClientException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TimeoutFloorTest {
  @Test
  void warmupBudgetFloorsInsteadOfStretchingASubMillisecondRemainder() {
    assertEquals(1, Elasticsearch7Adapter.readinessMillis(Duration.ofNanos(1_500_000)));
    assertEquals(
        ClientException.Kind.TIMEOUT,
        assertThrows(
                ClientException.class,
                () -> Elasticsearch7Adapter.readinessMillis(Duration.ofNanos(999_999)))
            .kind());
  }
}
