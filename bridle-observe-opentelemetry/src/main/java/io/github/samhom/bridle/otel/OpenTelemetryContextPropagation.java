package io.github.samhom.bridle.otel;

import io.github.samhom.bridle.core.ContextPropagation;
import io.opentelemetry.context.Context;

public final class OpenTelemetryContextPropagation implements ContextPropagation {
  @Override
  public Snapshot capture() {
    Context captured = Context.current();
    return () -> {
      io.opentelemetry.context.Scope scope = captured.makeCurrent();
      return scope::close;
    };
  }
}
