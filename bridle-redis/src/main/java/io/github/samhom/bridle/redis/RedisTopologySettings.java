package io.github.samhom.bridle.redis;

import io.github.samhom.bridle.core.*;
import java.time.Duration;
import java.util.*;

/**
 * Trusted infrastructure configuration. Sentinel credentials may differ from data-node credentials.
 */
public record RedisTopologySettings(
    Mode mode,
    Set<Endpoint> seeds,
    String masterName,
    CredentialsProvider credentials,
    CredentialsProvider sentinelCredentials,
    Duration connectTimeout,
    Duration readTimeout,
    int maxConnections)
    implements ImmutableSettings {
  public enum Mode {
    SENTINEL,
    CLUSTER
  }

  public RedisTopologySettings {
    Objects.requireNonNull(mode);
    seeds = Set.copyOf(seeds);
    Limits.count(seeds.size(), 32);
    Objects.requireNonNull(credentials);
    Objects.requireNonNull(sentinelCredentials);
    Limits.timeout(connectTimeout);
    Limits.timeout(readTimeout);
    Limits.count(maxConnections, 128);
    if (mode == Mode.SENTINEL
        && (masterName == null || !masterName.matches("[A-Za-z0-9_.-]{1,128}")))
      throw new IllegalArgumentException("Invalid Sentinel master name");
    boolean tls = seeds.iterator().next().tls();
    if (seeds.stream().anyMatch(seed -> seed.tls() != tls))
      throw new IllegalArgumentException("Mixed TLS seeds");
  }

  @Override
  public String toString() {
    return "RedisTopologySettings{redacted}";
  }
}
