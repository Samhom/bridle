package io.github.samhom.driverkit.spring;

import org.springframework.boot.actuate.health.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

/** Reports local lifecycle readiness only; does not start network probes on health requests. */
@AutoConfiguration(after = DriverKitAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "driverkit", name = "health-enabled", matchIfMissing = true)
public class DriverKitHealthAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(name = "driverKitHealthIndicator")
  public HealthIndicator driverKitHealthIndicator(DriverKitResources resources) {
    return () -> {
      long unhealthy =
          resources.clients().values().stream()
              .filter(c -> c.status().closed() || c.status().revoked() || c.cleanupFailures() > 0)
              .count();
      Health.Builder health = resources.isClosed() || unhealthy > 0 ? Health.down() : Health.up();
      return health
          .withDetail("resources", resources.clients().size())
          .withDetail("unhealthy", unhealthy)
          .build();
    };
  }
}
