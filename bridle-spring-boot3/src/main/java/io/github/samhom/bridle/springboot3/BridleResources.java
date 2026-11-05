package io.github.samhom.bridle.springboot3;

import io.github.samhom.bridle.core.*;
import java.util.*;
import org.springframework.beans.factory.BeanFactory;

/**
 * Owns only registrations created by this binding, including when the runtime is application-owned.
 */
public final class BridleResources implements AutoCloseable {
  private final Map<String, ManagedClient<?, ?>> clients = new LinkedHashMap<>();
  private volatile boolean closed;

  public BridleResources(
      ClientRuntime runtime,
      BridleProperties properties,
      List<ResourceFactory<?, ?>> factories,
      BeanFactory beans) {
    if (properties.getResources().size() > properties.getMaxClients())
      throw new IllegalArgumentException("Too many configured resources");
    // Resolve every descriptor before creating native resources. An invalid later entry must not
    // start earlier network clients or subscribe to credential sources.
    List<java.util.function.Supplier<ManagedClient<?, ?>>> prepared = new ArrayList<>();
    List<String> aliases = new ArrayList<>();
    for (var entry : properties.getResources().entrySet()) {
      ResourceProperties resource = Objects.requireNonNull(entry.getValue());
      var matches = factories.stream().filter(f -> f.driver() == resource.getDriver()).toList();
      if (matches.size() != 1)
        throw new IllegalArgumentException(
            "A configured driver requires exactly one ResourceFactory");
      CredentialsProvider credentials =
          beans.getBean(
              Objects.requireNonNull(resource.getCredentialsBean(), "credentials-bean"),
              CredentialsProvider.class);
      ResourceId id =
          new ResourceId(properties.getApplication(), properties.getEnvironment(), entry.getKey());
      prepared.add(prepare(runtime, id, resource, credentials, matches.get(0)));
      aliases.add(entry.getKey());
    }
    try {
      for (int i = 0; i < prepared.size(); i++) clients.put(aliases.get(i), prepared.get(i).get());
    } catch (RuntimeException | Error failure) {
      close();
      throw failure;
    }
  }

  private static <C, S> java.util.function.Supplier<ManagedClient<?, ?>> prepare(
      ClientRuntime runtime,
      ResourceId id,
      ResourceProperties p,
      CredentialsProvider credentials,
      ResourceFactory<C, S> factory) {
    S settings = factory.settings(p, credentials);
    DriverAdapter<C, S> adapter = factory.adapter();
    adapter.validate(settings);
    ConfigSnapshot<S> snapshot = new ConfigSnapshot<>(id, 1, settings);
    ClientPolicy policy =
        new ClientPolicy(p.getMaxInFlight(), p.getConnectTimeout(), p.getShutdownTimeout());
    return () -> {
      ManagedClient<C, S> client = runtime.register(snapshot, adapter, policy);
      try {
        if (credentials instanceof CredentialSource source) client.bindCredentials(source);
        return client;
      } catch (RuntimeException | Error failure) {
        client.close();
        throw failure;
      }
    };
  }

  public Map<String, ManagedClient<?, ?>> clients() {
    return Collections.unmodifiableMap(clients);
  }

  public boolean isClosed() {
    return closed;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    for (var client : clients.values()) client.close();
  }
}
