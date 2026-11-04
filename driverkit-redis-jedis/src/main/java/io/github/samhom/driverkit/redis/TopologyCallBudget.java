package io.github.samhom.driverkit.redis;

import io.github.samhom.driverkit.core.*;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/** One monotonic budget covers native borrowing, authentication and every redirect hop. */
final class TopologyCallBudget {
  private static final ThreadLocal<Supplier<Duration>> CURRENT = new ThreadLocal<>();

  static <T> T call(CallContext context, Callable<T> action) throws Exception {
    return scoped(context::remaining, action);
  }

  static <T> T call(Duration timeout, Callable<T> action) throws Exception {
    long deadline = System.nanoTime() + Limits.timeout(timeout).toNanos();
    return scoped(() -> Duration.ofNanos(Math.max(0, deadline - System.nanoTime())), action);
  }

  private static <T> T scoped(Supplier<Duration> remaining, Callable<T> action) throws Exception {
    Supplier<Duration> previous = CURRENT.get();
    CURRENT.set(
        previous == null
            ? remaining
            : () -> {
              Duration old = previous.get(), next = remaining.get();
              return old.compareTo(next) < 0 ? old : next;
            });
    try {
      return action.call();
    } finally {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }

  static int millis(Duration configured) {
    Supplier<Duration> budget = CURRENT.get();
    long nanos = configured.toNanos();
    if (budget != null) nanos = Math.min(nanos, budget.get().toNanos());
    if (nanos <= 0)
      throw new ClientException(
          ClientException.Kind.TIMEOUT, "Topology operation deadline exceeded");
    return (int) Math.max(1, Math.min(Integer.MAX_VALUE, (nanos + 999999) / 1000000));
  }
}
