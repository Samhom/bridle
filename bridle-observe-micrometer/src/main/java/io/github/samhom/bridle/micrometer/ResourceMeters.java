package io.github.samhom.bridle.micrometer;

import io.github.samhom.bridle.core.*;
import io.micrometer.core.instrument.*;
import java.util.*;

/** Explicit-lifetime gauges; close this binding when removing the resource. */
public final class ResourceMeters implements AutoCloseable {
  private final MeterRegistry registry;
  private final List<Meter> meters = new ArrayList<>();
  private boolean closed;

  public ResourceMeters(MeterRegistry registry, ManagedClient<?, ?> client) {
    this.registry = Objects.requireNonNull(registry);
    Objects.requireNonNull(client);
    ResourceId resource = client.resource();
    Tags tags =
        Tags.of(
            "application",
            resource.application(),
            "environment",
            resource.environment(),
            "resource",
            resource.alias());
    synchronized (registry) {
      String[] names = {
        "inflight",
        "queue.size",
        "workers.active",
        "queue.capacity",
        "cleanup.failures",
        "configuration.rejections",
        "retiring.age"
      };
      for (String name : names)
        if (registry.find("bridle.resource." + name).tags(tags).meter() != null)
          throw new IllegalArgumentException("Resource meters already bound");
      for (int i = 0; i < names.length; i++) {
        final int field = i;
        meters.add(
            Gauge.builder(
                    "bridle.resource." + names[i],
                    client,
                    c -> {
                      RuntimeMetrics m = c.metrics();
                      switch (field) {
                        case 0:
                          return m.inFlight;
                        case 1:
                          return m.queuedCalls;
                        case 2:
                          return m.activeWorkers;
                        case 3:
                          return m.queueCapacity;
                        case 4:
                          return m.cleanupFailures;
                        case 5:
                          return m.rejectedUpdates;
                        default:
                          return m.retiringAgeNanos / 1_000_000_000.0;
                      }
                    })
                .tags(tags)
                .register(registry));
      }
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    synchronized (registry) {
      for (Meter meter : meters) registry.remove(meter);
      meters.clear();
    }
  }
}
