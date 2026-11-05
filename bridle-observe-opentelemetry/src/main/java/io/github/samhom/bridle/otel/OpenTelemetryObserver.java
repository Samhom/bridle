package io.github.samhom.bridle.otel;

import io.github.samhom.bridle.core.*;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** SDK-owned spans omit query text, keys, payloads, credentials and exception messages. */
public final class OpenTelemetryObserver implements Observer {
  private final Tracer tracer;

  public OpenTelemetryObserver(OpenTelemetry telemetry) {
    tracer =
        Objects.requireNonNull(telemetry, "telemetry")
            .getTracer("io.github.samhom.bridle", "0.1.0");
  }

  @Override
  public Observation start(ResourceId resource, String driver, Operation operation, long revision) {
    Span span =
        tracer
            .spanBuilder("bridle." + operation.name())
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("bridle.driver", driver)
            .setAttribute("bridle.resource", resource.alias())
            .setAttribute("bridle.application", resource.application())
            .setAttribute("bridle.environment", resource.environment())
            .setAttribute("bridle.config.revision", revision)
            .startSpan();
    return new Observation() {
      private final AtomicBoolean finished = new AtomicBoolean();

      @Override
      public AutoCloseable openScope() {
        return span.makeCurrent();
      }

      @Override
      public void finish(Outcome outcome, long elapsedNanos) {
        if (!finished.compareAndSet(false, true)) return;
        span.setAttribute("bridle.outcome", outcome.name());
        if (outcome == Outcome.ERROR || outcome == Outcome.TIMEOUT || outcome == Outcome.REJECTED)
          span.setStatus(StatusCode.ERROR);
        span.end();
      }
    };
  }
}
