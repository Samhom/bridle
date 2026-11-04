package io.github.samhom.driverkit.spring;

import io.github.samhom.driverkit.core.*;
import java.util.*;
import org.springframework.beans.factory.BeanFactory;

/**
 * Owns only registrations created by this binding, including when the runtime is application-owned.
 */
public final class DriverKitResources implements AutoCloseable {
  private final Map<String, ManagedClient<?, ?>> clients = new LinkedHashMap<>();
  private volatile boolean closed;

  public DriverKitResources(
      ClientRuntime runtime,
      DriverKitProperties properties,
      List<ResourceFactory<?, ?>> factories,
      BeanFactory beans) {
    if (properties.getResources().size() > properties.getMaxClients())
      throw new IllegalArgumentException("Too many configured resources");
    try {
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
            new ResourceId(
                properties.getApplication(), properties.getEnvironment(), entry.getKey());
        clients.put(entry.getKey(), register(runtime, id, resource, credentials, matches.get(0)));
      }
    } catch (RuntimeException | Error failure) {
      close();
      throw failure;
    }
  }

  private static <C, S> ManagedClient<C, S> register(
      ClientRuntime runtime,
      ResourceId id,
      ResourceProperties p,
      CredentialsProvider credentials,
      ResourceFactory<C, S> factory) {
    return runtime.register(
        new ConfigSnapshot<>(id, 1, factory.settings(p, credentials)),
        factory.adapter(),
        new ClientPolicy(p.getMaxInFlight(), p.getConnectTimeout(), p.getShutdownTimeout()));
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
