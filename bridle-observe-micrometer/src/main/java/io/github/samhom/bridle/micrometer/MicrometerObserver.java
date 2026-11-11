package io.github.samhom.bridle.micrometer;

import io.github.samhom.bridle.core.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Uses the application's registry; bounded identities prevent arbitrary operation names exploding
 * series.
 */
public final class MicrometerObserver implements Observer {
  private final MeterRegistry registry;
  private final int maxIdentities;
  private final Set<String> identities = new HashSet<>();
  private final ConcurrentHashMap<String, Instruments> instruments = new ConcurrentHashMap<>();
  private volatile Instruments overflow;

  public MicrometerObserver(MeterRegistry registry) {
    this(registry, 4096);
  }

  public MicrometerObserver(MeterRegistry registry, int maxIdentities) {
    this.registry = Objects.requireNonNull(registry, "registry");
    if (maxIdentities < 1 || maxIdentities > 100000)
      throw new IllegalArgumentException("Invalid metric identity limit");
    this.maxIdentities = maxIdentities;
  }

  @Override
  public Observation start(ResourceId resource, String driver, Operation operation, long revision) {
    String identity = resource + "/" + driver + "/" + operation.name();
    boolean admitted;
    synchronized (identities) {
      admitted = identities.contains(identity) || identities.size() < maxIdentities;
      if (admitted) identities.add(identity);
    }
    Instruments meters = admitted ? admitted(identity, resource, driver, operation) : overflow();
    return new Observation() {
      private final AtomicBoolean finished = new AtomicBoolean();
      private long queueNanos;

      @Override
      public void queued(long nanos) {
        queueNanos = Math.max(0, nanos);
      }

      @Override
      public void finish(Outcome outcome, long elapsed) {
        if (!finished.compareAndSet(false, true)) return;
        if (queueNanos > 0) meters.queue().record(queueNanos, TimeUnit.NANOSECONDS);
        meters.counter(outcome).increment();
        meters.timer(outcome).record(Math.max(0, elapsed), TimeUnit.NANOSECONDS);
      }
    };
  }

  private Instruments admitted(
      String identity, ResourceId resource, String driver, Operation operation) {
    Instruments existing = instruments.get(identity);
    if (existing != null) return existing;
    Tags tags =
        Tags.of(
            "application",
            resource.application(),
            "environment",
            resource.environment(),
            "resource",
            resource.alias(),
            "driver",
            driver,
            "operation",
            operation.name());
    return instruments.computeIfAbsent(identity, key -> new Instruments(tags));
  }

  private Instruments overflow() {
    Instruments existing = overflow;
    if (existing != null) return existing;
    synchronized (this) {
      if (overflow == null)
        overflow =
            new Instruments(
                Tags.of(
                    "application",
                    "overflow",
                    "environment",
                    "overflow",
                    "resource",
                    "overflow",
                    "driver",
                    "overflow",
                    "operation",
                    "overflow"));
      return overflow;
    }
  }

  private final class Instruments {
    private final Tags tags;
    private final AtomicReference<Timer> queue = new AtomicReference<>();
    private final AtomicReferenceArray<Counter> counters =
        new AtomicReferenceArray<>(Observation.Outcome.values().length);
    private final AtomicReferenceArray<Timer> timers =
        new AtomicReferenceArray<>(Observation.Outcome.values().length);

    Instruments(Tags tags) {
      this.tags = tags;
    }

    Timer queue() {
      Timer existing = queue.get();
      if (existing != null) return existing;
      Timer created = Timer.builder("bridle.client.queue.wait").tags(tags).register(registry);
      return queue.compareAndSet(null, created) ? created : queue.get();
    }

    Counter counter(Observation.Outcome outcome) {
      return meter(counters, outcome, true);
    }

    Timer timer(Observation.Outcome outcome) {
      return meter(timers, outcome, false);
    }

    @SuppressWarnings("unchecked")
    private <T> T meter(
        AtomicReferenceArray<T> meters, Observation.Outcome outcome, boolean count) {
      int index = outcome.ordinal();
      T existing = meters.get(index);
      if (existing != null) return existing;
      Tags result = tags.and("outcome", outcome.name().toLowerCase(Locale.ROOT));
      T created =
          count
              ? (T) registry.counter("bridle.client.operations", result)
              : (T)
                  Timer.builder("bridle.client.operation.duration").tags(result).register(registry);
      return meters.compareAndSet(index, null, created) ? created : meters.get(index);
    }
  }
}
