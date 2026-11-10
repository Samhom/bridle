package io.github.samhom.bridle.core;

import java.util.Objects;

/** Capture on the submitting thread; every open must restore the receiving thread on close. */
@FunctionalInterface
public interface ContextPropagation {
  Snapshot capture();

  interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  @FunctionalInterface
  interface Snapshot {
    Scope open();

    default void run(Runnable action) {
      try (Scope ignored = open()) {
        action.run();
      }
    }
  }

  ContextPropagation NONE = () -> NoopPropagation.SNAPSHOT;

  static ContextPropagation combine(ContextPropagation... providers) {
    ContextPropagation[] copy = providers.clone();
    for (ContextPropagation provider : copy) Objects.requireNonNull(provider);
    if (copy.length == 2)
      return () -> CombinedPropagation.pair(copy[0].capture(), copy[1].capture());
    return () -> {
      Snapshot[] snapshots = new Snapshot[copy.length];
      for (int i = 0; i < copy.length; i++)
        snapshots[i] = Objects.requireNonNull(copy[i].capture());
      return () -> CombinedPropagation.openAll(snapshots);
    };
  }
}

/** Shared no-op scope. A normal call captures this instead of allocating another lambda. */
final class NoopPropagation {
  static final ContextPropagation.Scope SCOPE = () -> {};
  static final ContextPropagation.Snapshot SNAPSHOT = () -> SCOPE;

  private NoopPropagation() {}
}

final class CombinedPropagation {
  private CombinedPropagation() {}

  static ContextPropagation.Snapshot pair(
      ContextPropagation.Snapshot first, ContextPropagation.Snapshot second) {
    Objects.requireNonNull(first, "first");
    Objects.requireNonNull(second, "second");
    if (trivial(first) && trivial(second))
      return first == CallContexts.emptyCapture() || second == CallContexts.emptyCapture()
          ? CallContexts.emptyCapture()
          : NoopPropagation.SNAPSHOT;
    return () -> {
      ContextPropagation.Scope opened = first.open();
      ContextPropagation.Scope next;
      try {
        next = second.open();
      } catch (RuntimeException | Error failure) {
        closeQuietly(opened, failure);
        throw failure;
      }
      ContextPropagation.Scope secondScope = next;
      return () -> closePair(secondScope, opened);
    };
  }

  static ContextPropagation.Scope openAll(ContextPropagation.Snapshot[] snapshots) {
    ContextPropagation.Scope[] scopes = new ContextPropagation.Scope[snapshots.length];
    int opened = 0;
    try {
      for (; opened < snapshots.length; opened++)
        scopes[opened] = Objects.requireNonNull(snapshots[opened].open());
    } catch (RuntimeException | Error failure) {
      for (int i = opened - 1; i >= 0; i--) closeQuietly(scopes[i], failure);
      throw failure;
    }
    return () -> {
      Throwable failure = null;
      for (int i = scopes.length - 1; i >= 0; i--) {
        try {
          scopes[i].close();
        } catch (RuntimeException | Error error) {
          if (failure == null) failure = error;
          else failure.addSuppressed(error);
        }
      }
      if (failure instanceof Error) throw (Error) failure;
      if (failure != null) throw (RuntimeException) failure;
    };
  }

  private static boolean trivial(ContextPropagation.Snapshot snapshot) {
    return snapshot == NoopPropagation.SNAPSHOT || snapshot == CallContexts.emptyCapture();
  }

  private static void closePair(ContextPropagation.Scope second, ContextPropagation.Scope first) {
    Throwable failure = null;
    try {
      second.close();
    } catch (RuntimeException | Error error) {
      failure = error;
    }
    try {
      first.close();
    } catch (RuntimeException | Error error) {
      if (failure == null) failure = error;
      else failure.addSuppressed(error);
    }
    if (failure instanceof Error) throw (Error) failure;
    if (failure != null) throw (RuntimeException) failure;
  }

  private static void closeQuietly(ContextPropagation.Scope scope, Throwable failure) {
    try {
      scope.close();
    } catch (RuntimeException | Error cleanup) {
      failure.addSuppressed(cleanup);
    }
  }
}
