package io.github.samhom.bridle.core;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Reserves delivery capacity before admission; completion never falls back to a native thread. */
final class CompletionDispatcher {
  private static final ThreadLocal<Boolean> IN_COMPLETION = new ThreadLocal<>();
  private final ThreadPoolExecutor executor;
  private final int capacity;
  private int reservations;
  private boolean closed;

  CompletionDispatcher(int threads, int capacity) {
    this(executor(threads, capacity), capacity);
  }

  CompletionDispatcher(ThreadPoolExecutor executor, int capacity) {
    if (capacity < 1 || capacity > 100000)
      throw new IllegalArgumentException("Completion limits out of bounds");
    this.capacity = capacity;
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  private static ThreadPoolExecutor executor(int threads, int capacity) {
    if (threads < 1 || threads > 1024)
      throw new IllegalArgumentException("Completion limits out of bounds");
    AtomicInteger ids = new AtomicInteger();
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            threads,
            threads,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(capacity),
            task -> {
              Thread thread = new Thread(task, "bridle-completion-" + ids.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  static boolean inCompletionThread() {
    return Boolean.TRUE.equals(IN_COMPLETION.get());
  }

  synchronized Reservation reserve() {
    if (closed) throw new ClientException(ClientException.Kind.CLOSED, "Runtime is closed");
    if (reservations >= capacity)
      throw new ClientException(ClientException.Kind.OVERLOADED, "Completion capacity exhausted");
    reservations++;
    return new Reservation();
  }

  final class Reservation {
    private static final int DELIVERED = 1;
    private static final int RELEASED = 2;
    private final AtomicInteger state = new AtomicInteger();

    void deliver(Runnable completion) {
      if (!mark(DELIVERED)) throw new IllegalStateException("Completion already selected");
      // At most capacity reservations exist. Queue capacity alone covers all of them, even
      // while a worker is returning from the finally block that releases its reservation.
      try {
        executor.execute(
            () -> {
              IN_COMPLETION.set(true);
              try {
                completion.run();
              } finally {
                IN_COMPLETION.remove();
                release();
              }
            });
      } catch (RuntimeException | Error failure) {
        release();
        throw failure;
      }
    }

    void release() {
      if (!mark(RELEASED)) return;
      synchronized (CompletionDispatcher.this) {
        reservations--;
        if (closed && reservations == 0) executor.shutdown();
      }
    }

    private boolean mark(int bit) {
      int current;
      do {
        current = state.get();
        if ((current & bit) != 0) return false;
      } while (!state.compareAndSet(current, current | bit));
      return true;
    }
  }

  synchronized void close() {
    closed = true;
    if (reservations == 0) executor.shutdown();
  }

  void awaitClose(long deadline) throws InterruptedException {
    if (inCompletionThread()) return;
    executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
  }
}
