package io.github.samhom.bridle.test;

import java.time.Duration;
import java.util.function.LongSupplier;

/** A clock tests move forward themselves. */
public final class ManualClock implements LongSupplier {
  private long nanos;

  @Override
  public long getAsLong() {
    return nanos;
  }

  public void advance(Duration duration) {
    nanos += duration.toNanos();
  }
}
