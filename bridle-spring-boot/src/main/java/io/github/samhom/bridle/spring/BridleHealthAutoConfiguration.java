package io.github.samhom.bridle.spring;

import io.github.samhom.bridle.core.*;
import org.springframework.boot.actuate.health.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

/** Reports local lifecycle readiness only; does not start network probes on health requests. */
@AutoConfiguration(after = BridleAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "bridle", name = "health-enabled", matchIfMissing = true)
public class BridleHealthAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(name = "bridleHealthIndicator")
  public HealthIndicator bridleHealthIndicator(BridleResources resources) {
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
