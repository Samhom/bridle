package io.github.samhom.bridle.core;

import java.util.concurrent.atomic.AtomicBoolean;

/** Pins a generation for transactions, pipelines or streams. Do not let native objects escape. */
public final class ClientSession<C> implements AutoCloseable {
  private final C client;
  private final Object owner;
  private final java.util.function.Supplier<ClientSession<C>> retainer;
  private final long revision;
  private final Runnable release;
  private final java.util.function.BooleanSupplier usable;
  private final Object settings;
  private final AtomicBoolean closed = new AtomicBoolean();

  ClientSession(
      C client,
      long revision,
      Object settings,
      Runnable release,
      java.util.function.BooleanSupplier usable,
      Object owner,
      java.util.function.Supplier<ClientSession<C>> retainer) {
    this.client = client;
    this.owner = owner;
    this.retainer = retainer;
    this.revision = revision;
    this.settings = settings;
    this.release = release;
    this.usable = usable;
  }

  public <S> S settings(Class<S> type) {
    return type.cast(settings);
  }

  public C client() {
    if (closed.get() || !usable.getAsBoolean())
      throw new ClientException(ClientException.Kind.CLOSED, "Session is closed");
    return client;
  }

  public long revision() {
    return revision;
  }

  synchronized ClientSession<C> retainFor(Object expectedOwner) {
    if (owner != expectedOwner)
      throw new IllegalArgumentException("Session belongs to another managed client");
    if (closed.get()) throw new ClientException(ClientException.Kind.CLOSED, "Session is closed");
    return retainer.get();
  }

  @Override
  public synchronized void close() {
    if (closed.compareAndSet(false, true)) release.run();
  }
}
