package io.github.samhom.driverkit.spring;

import io.github.samhom.driverkit.core.*;

/** Extension point for a typed driver; credential values are supplied by an application bean. */
public interface ResourceFactory<C, S> {
  ResourceProperties.Driver driver();

  S settings(ResourceProperties properties, CredentialsProvider credentials);

  DriverAdapter<C, S> adapter();
}
