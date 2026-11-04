package io.github.samhom.driverkit.spring;

import io.github.samhom.driverkit.core.*;
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
      int unhealthy = 0, rotating = 0;
      for (var client : resources.clients().values()) {
        ClientStatus status = client.status();
        CredentialStatus credential = client.credentialStatus();
        if (status.closed()
            || status.revoked()
            || client.cleanupFailures() > 0
            || credential != null
                && (credential.state() == CredentialStatus.State.FAILED
                    || credential.state() == CredentialStatus.State.REVOKED
                    || credential.state() == CredentialStatus.State.CLOSED)) unhealthy++;
        else if (credential != null && credential.state() == CredentialStatus.State.ROTATING)
          rotating++;
      }
      Health.Builder health =
          resources.isClosed() || unhealthy > 0
              ? Health.down()
              : rotating > 0 ? Health.outOfService() : Health.up();
      return health
          .withDetail("resources", resources.clients().size())
          .withDetail("unhealthy", unhealthy)
          .withDetail("rotating", rotating)
          .build();
    };
  }
}
