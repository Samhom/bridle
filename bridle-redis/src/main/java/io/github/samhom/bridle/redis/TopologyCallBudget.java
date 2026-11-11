package io.github.samhom.bridle.redis;

import io.github.samhom.bridle.core.*;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.LongSupplier;

/** One monotonic budget covers native borrowing, authentication and every redirect hop. */
final class TopologyCallBudget {
  private static final ThreadLocal<LongSupplier> CURRENT = new ThreadLocal<>();

  static <T> T call(CallContext context, Callable<T> action) throws Exception {
    return scoped(context::remainingNanos, action);
  }

  static <T> T call(Duration timeout, Callable<T> action) throws Exception {
    long deadline = System.nanoTime() + Limits.timeout(timeout).toNanos();
    return scoped(() -> Math.max(0, deadline - System.nanoTime()), action);
  }

  private static <T> T scoped(LongSupplier remaining, Callable<T> action) throws Exception {
    LongSupplier previous = CURRENT.get();
    CURRENT.set(
        previous == null ? remaining : () -> Math.min(previous.getAsLong(), remaining.getAsLong()));
    try {
      return action.call();
    } finally {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }

  static int millis(Duration configured) {
    LongSupplier budget = CURRENT.get();
    long nanos = configured.toNanos();
    if (budget != null) nanos = Math.min(nanos, budget.getAsLong());
    return CallContext.millisWithin(nanos);
  }
}
