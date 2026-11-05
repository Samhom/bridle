package io.github.samhom.bridle.core;

/** Immutable opt-in resource isolation. Circuit settings live on {@code CircuitBreakerConfig}. */
public final class ExecutionPolicy {
  private final int threads, queue;

  private ExecutionPolicy(int threads, int queue) {
    this.threads = threads;
    this.queue = queue;
  }

  public static ExecutionPolicy shared() {
    return new ExecutionPolicy(0, 0);
  }

  public static ExecutionPolicy isolated(int threads, int queue) {
    return new ExecutionPolicy(Limits.count(threads, 64), Limits.count(queue, 10000));
  }

  public int threads() {
    return threads;
  }

  public int queuedCalls() {
    return queue;
  }
}
