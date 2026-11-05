package io.github.samhom.bridle.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DiagnosticBufferTest {
  @Test
  void boundedSampledAndIdempotent() {
    DiagnosticBuffer buffer = new DiagnosticBuffer(2, Duration.ofMillis(1), 2);
    ResourceId resource = new ResourceId("test", "test", "test");
    for (int i = 0; i < 10; i++) {
      Observation observation =
          buffer.start(resource, "fixture", Operation.read("get", Duration.ofSeconds(1)), i);
      observation.finish(Observation.Outcome.ERROR, 10);
      observation.finish(Observation.Outcome.ERROR, 10);
    }
    assertEquals(2, buffer.snapshot().size());
    assertEquals(6, buffer.snapshot().get(0).revision);
    assertEquals(8, buffer.snapshot().get(1).revision);
    assertThrows(UnsupportedOperationException.class, () -> buffer.snapshot().clear());
    buffer.clear();
    assertTrue(buffer.snapshot().isEmpty());
  }
}
