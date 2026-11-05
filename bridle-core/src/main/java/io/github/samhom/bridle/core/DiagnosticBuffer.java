package io.github.samhom.bridle.core;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in bounded metadata-only flight recorder. Never retains payloads, settings or exceptions. */
public final class DiagnosticBuffer implements Observer {
  public enum Kind {
    SLOW_CALL,
    CALL_FAILURE,
    CONFIGURATION_FAILURE,
    CLEANUP_FAILURE
  }

  public static final class Event {
    public final Instant time;
    public final ResourceId resource;
    public final Kind kind;
    public final long revision, elapsedNanos;
    public final Observation.Outcome outcome;

    Event(
        ResourceId resource, Kind kind, long revision, long elapsed, Observation.Outcome outcome) {
      time = Instant.now();
      this.resource = resource;
      this.kind = kind;
      this.revision = revision;
      elapsedNanos = Math.max(0, elapsed);
      this.outcome = outcome;
    }
  }

  private final int capacity, sampleEvery;
  private final long slowNanos;
  private final AtomicLong sequence = new AtomicLong();
  private final ArrayDeque<Event> events = new ArrayDeque<>();

  public DiagnosticBuffer(int capacity, Duration slowThreshold, int sampleEvery) {
    if (capacity < 1 || capacity > 10000 || sampleEvery < 1 || sampleEvery > 1000000)
      throw new IllegalArgumentException("Diagnostic limits out of bounds");
    this.capacity = capacity;
    this.sampleEvery = sampleEvery;
    slowNanos = Limits.timeout(slowThreshold).toNanos();
  }

  private synchronized void add(Event event) {
    if (events.size() == capacity) events.removeFirst();
    events.addLast(event);
  }

  public synchronized List<Event> snapshot() {
    return Collections.unmodifiableList(new ArrayList<>(events));
  }

  public synchronized void clear() {
    events.clear();
  }

  /**
   * Records only publication metadata; callers may poll configurationHistory without retaining
   * causes.
   */
  public void configuration(ResourceId resource, ConfigurationStatus status) {
    Objects.requireNonNull(resource);
    Objects.requireNonNull(status);
    if (status.phase() == ConfigurationStatus.Phase.FAILED)
      add(
          new Event(
              resource,
              Kind.CONFIGURATION_FAILURE,
              status.attemptedRevision(),
              status.elapsedNanos(),
              Observation.Outcome.ERROR));
  }

  @Override
  public Observation start(ResourceId resource, String driver, Operation operation, long revision) {
    Objects.requireNonNull(resource);
    if (Math.floorMod(sequence.getAndIncrement(), sampleEvery) != 0) return Observation.NOOP;
    java.util.concurrent.atomic.AtomicBoolean done =
        new java.util.concurrent.atomic.AtomicBoolean();
    return (outcome, elapsed) -> {
      if (!done.compareAndSet(false, true)) return;
      if (outcome != Observation.Outcome.SUCCESS || elapsed >= slowNanos)
        add(
            new Event(
                resource,
                outcome == Observation.Outcome.SUCCESS ? Kind.SLOW_CALL : Kind.CALL_FAILURE,
                revision,
                elapsed,
                outcome));
    };
  }
}
