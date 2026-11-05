package io.github.samhom.bridle.micrometer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.samhom.bridle.core.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MicrometerObserverTest {
  private static final ResourceId RESOURCE = new ResourceId("example", "test", "cache");

  @Test
  void runtimeGaugesAreExplicitlyReleasedAndQueueTimingIsRecorded() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (ClientRuntime runtime = new ClientRuntime()) {
      var client =
          runtime.register(
              new ConfigSnapshot<>(RESOURCE, 1, "fixture"),
              new FactoryAdapter<String, String>("fixture", s -> s, (c, t) -> {}, c -> {}),
              ClientPolicy.defaults());
      try (ResourceMeters meters = new ResourceMeters(registry, client)) {
        assertEquals(0, registry.get("bridle.resource.inflight").gauge().value());
        assertThrows(IllegalArgumentException.class, () -> new ResourceMeters(registry, client));
      }
      assertTrue(registry.getMeters().isEmpty());
      Observation observation =
          new MicrometerObserver(registry)
              .start(RESOURCE, "fixture", Operation.read("get", Duration.ofSeconds(1)), 1);
      observation.queued(1000);
      observation.finish(Observation.Outcome.SUCCESS, 2000);
      assertEquals(1, registry.get("bridle.client.queue.wait").timer().count());
    } finally {
      registry.close();
    }
  }

  @Test
  void recordsOneLogicalResultWithoutChangingInjectedRegistry() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try {
      Observation observation =
          new MicrometerObserver(registry)
              .start(RESOURCE, "redis", Operation.read("get", Duration.ofSeconds(1)), 1);
      observation.finish(Observation.Outcome.SUCCESS, 1000000);
      observation.finish(Observation.Outcome.ERROR, 2000000);
      assertEquals(
          1, registry.get("bridle.client.operations").tag("outcome", "success").counter().count());
      assertEquals(1, registry.get("bridle.client.operation.duration").timer().count());
      assertEquals(2, registry.getMeters().size());
    } finally {
      registry.close();
    }
  }

  @Test
  void capsMetricCardinalityWithASingleOverflowIdentity() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try {
      MicrometerObserver observer = new MicrometerObserver(registry, 1);
      for (int i = 0; i < 100; i++)
        observer
            .start(RESOURCE, "redis", Operation.read("operation" + i, Duration.ofSeconds(1)), 1)
            .finish(Observation.Outcome.SUCCESS, 1);
      assertEquals(4, registry.getMeters().size());
      assertEquals(
          99,
          registry.get("bridle.client.operations").tag("resource", "overflow").counter().count());
    } finally {
      registry.close();
    }
  }
}
