package io.github.samhom.bridle.spring;

import static org.junit.jupiter.api.Assertions.*;

import io.github.samhom.bridle.core.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class ResourceLifecycleTest {
  record Settings(CredentialsProvider credentials) implements ImmutableSettings {}

  static ResourceProperties resource(String bean) {
    var p = new ResourceProperties();
    p.setDriver(ResourceProperties.Driver.REDIS);
    p.setCredentialsBean(bean);
    return p;
  }

  static ResourceFactory<String, Settings> factory(AtomicInteger creates, AtomicInteger closes) {
    return new ResourceFactory<>() {
      public ResourceProperties.Driver driver() {
        return ResourceProperties.Driver.REDIS;
      }

      public Settings settings(ResourceProperties p, CredentialsProvider c) {
        return new Settings(c);
      }

      public DriverAdapter<String, Settings> adapter() {
        return new DriverAdapter<>() {
          public String id() {
            return "fixture";
          }

          public void validate(Settings s) {}

          public CredentialsProvider credentials(Settings s) {
            return s.credentials();
          }

          public String create(Settings s) {
            creates.incrementAndGet();
            try (Credentials c = s.credentials().resolve()) {
              return c.username();
            }
          }

          public void warmup(String c, Duration d) {}

          public void close(String c) {
            closes.incrementAndGet();
          }
        };
      }
    };
  }

  static void await(java.util.function.BooleanSupplier test) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!test.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
    assertTrue(test.getAsBoolean());
  }

  @Test
  void badLaterDescriptorDoesNotStartEarlierClients() {
    var creates = new AtomicInteger();
    var closes = new AtomicInteger();
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton(
        "valid", (CredentialsProvider) () -> new Credentials("fixture", new char[0]));
    var p = new BridleProperties();
    var resources = new LinkedHashMap<String, ResourceProperties>();
    resources.put("first", resource("valid"));
    resources.put("second", resource("missing"));
    p.setResources(resources);
    try (var runtime = new ClientRuntime()) {
      assertThrows(
          org.springframework.beans.BeansException.class,
          () -> new BridleResources(runtime, p, List.of(factory(creates, closes)), beans));
      assertEquals(0, creates.get());
      assertEquals(0, runtime.clientCount());
    }
  }

  @Test
  void rotatingCredentialBeanActuallyRotatesAndRevokesManagedRegistration() throws Exception {
    var creates = new AtomicInteger();
    var closes = new AtomicInteger();
    var beans = new DefaultListableBeanFactory();
    var source =
        new RotatingCredentialsProvider(
            () -> new Credentials("one", new char[0]),
            new CredentialState(
                1, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120), false),
            () -> {});
    beans.registerSingleton("source", source);
    var p = new BridleProperties();
    p.setResources(Map.of("cache", resource("source")));
    try (var runtime = new ClientRuntime()) {
      var external =
          runtime.register(
              new ConfigSnapshot<>(new ResourceId("app", "test", "external"), 1, "external"),
              new FactoryAdapter<String, String>("external", s -> s, (c, d) -> {}, c -> {}),
              ClientPolicy.defaults());
      try (var resources =
          new BridleResources(runtime, p, List.of(factory(creates, closes)), beans)) {
        var client = resources.clients().get("cache");
        await(() -> client.credentialStatus().state() == CredentialStatus.State.ACTIVE);
        source.rotate(
            () -> new Credentials("two", new char[0]),
            new CredentialState(
                2, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120), false));
        await(() -> client.credentialStatus().activeVersion() == 2);
        assertEquals(1, client.status().revision());
        var health = new BridleHealthAutoConfiguration().bridleHealthIndicator(resources);
        assertEquals(
            org.springframework.boot.actuate.health.Status.UP, health.health().getStatus());
        source.revoke(3);
        await(() -> client.status().revoked());
        assertEquals(
            org.springframework.boot.actuate.health.Status.DOWN, health.health().getStatus());
      }
      assertFalse(external.status().closed());
      await(() -> creates.get() == closes.get());
    }
  }
}
