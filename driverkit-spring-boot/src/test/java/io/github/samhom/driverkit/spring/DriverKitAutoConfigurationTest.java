package io.github.samhom.driverkit.spring;

import static org.junit.jupiter.api.Assertions.*;

import io.github.samhom.driverkit.core.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class DriverKitAutoConfigurationTest {
  @Test
  void bindsTypedResourcesAndHealthWithoutNativeNetworkCalls() {
    var closed = new java.util.concurrent.atomic.AtomicInteger();
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "resources",
                  Map.of(
                      "driverkit.application",
                      "fixture",
                      "driverkit.environment",
                      "test",
                      "driverkit.resources.cache.driver",
                      "REDIS",
                      "driverkit.resources.cache.host",
                      "localhost",
                      "driverkit.resources.cache.port",
                      "6379",
                      "driverkit.resources.cache.database",
                      "3",
                      "driverkit.resources.cache.credentials-bean",
                      "fixtureCredentials")));
      context.registerBean(
          "fixtureCredentials",
          CredentialsProvider.class,
          () -> () -> new Credentials("fixture", new char[0]));
      context.registerBean(
          "fixtureFactory",
          ResourceFactory.class,
          () ->
              new ResourceFactory<String, String>() {
                public ResourceProperties.Driver driver() {
                  return ResourceProperties.Driver.REDIS;
                }

                public String settings(ResourceProperties p, CredentialsProvider c) {
                  assertEquals(3, p.getDatabase());
                  assertTrue(p.isTls());
                  return "fixture";
                }

                public DriverAdapter<String, String> adapter() {
                  return new FactoryAdapter<>(
                      "fixture", v -> v, (c, t) -> {}, c -> closed.incrementAndGet());
                }
              });
      context.register(DriverKitAutoConfiguration.class, DriverKitHealthAutoConfiguration.class);
      context.refresh();
      var resources = context.getBean(DriverKitResources.class);
      assertEquals(1, resources.clients().size());
      var health = context.getBean(org.springframework.boot.actuate.health.HealthIndicator.class);
      assertEquals(org.springframework.boot.actuate.health.Status.UP, health.health().getStatus());
      resources.clients().get("cache").revoke();
      assertEquals(
          org.springframework.boot.actuate.health.Status.DOWN, health.health().getStatus());
      assertFalse(health.health().getDetails().toString().contains("localhost"));
    }
    assertEquals(1, closed.get());
  }

  @Test
  void refusesConfiguredDriverWhenNoFactoryIsAvailable() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "resources",
                  Map.of(
                      "driverkit.resources.cache.driver",
                      "REDIS",
                      "driverkit.resources.cache.credentials-bean",
                      "missing")));
      context.register(DriverKitAutoConfiguration.class);
      assertThrows(org.springframework.beans.BeansException.class, context::refresh);
    }
  }

  @Test
  void createsConfiguredRuntimeAndClosesItWithApplicationContext() {
    ClientRuntime runtime;
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "test",
                  Map.of(
                      "driverkit.max-clients",
                      "1",
                      "driverkit.call-threads",
                      "1",
                      "driverkit.queued-calls",
                      "2")));
      context.register(DriverKitAutoConfiguration.class);
      context.refresh();
      assertEquals(1, context.getBean(DriverKitProperties.class).getMaxClients());
      runtime = context.getBean(ClientRuntime.class);
      FactoryAdapter<String, String> adapter =
          new FactoryAdapter<>("test", value -> value, (value, timeout) -> {}, value -> {});
      runtime.register(
          new ConfigSnapshot<>(new ResourceId("app", "test", "one"), 1, "value"),
          adapter,
          ClientPolicy.defaults());
      assertThrows(
          ClientException.class,
          () ->
              runtime.register(
                  new ConfigSnapshot<>(new ResourceId("app", "test", "two"), 1, "value"),
                  adapter,
                  ClientPolicy.defaults()));
    }
    FactoryAdapter<String, String> adapter =
        new FactoryAdapter<>("test", value -> value, (value, timeout) -> {}, value -> {});
    assertThrows(
        ClientException.class,
        () ->
            runtime.register(
                new ConfigSnapshot<>(new ResourceId("app", "test", "later"), 1, "value"),
                adapter,
                ClientPolicy.defaults()));
  }
}
