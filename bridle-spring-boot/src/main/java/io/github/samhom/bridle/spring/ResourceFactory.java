package io.github.samhom.bridle.spring;

import io.github.samhom.bridle.core.*;

/** Extension point for a typed driver; credential values are supplied by an application bean. */
public interface ResourceFactory<C, S> {
  ResourceProperties.Driver driver();

  S settings(ResourceProperties properties, CredentialsProvider credentials);

  DriverAdapter<C, S> adapter();
}
