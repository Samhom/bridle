package io.github.samhom.driverkit.redis;

import io.github.samhom.driverkit.core.*;
import java.time.Duration;
import java.util.*;
import redis.clients.jedis.*;

/** Native Sentinel discovery / MOVED and ASK routing; no DriverKit command replay. */
public final class RedisTopologyAdapter
    implements DriverAdapter<UnifiedJedis, RedisTopologySettings> {
  public String id() {
    return "redis-jedis8-topology";
  }

  public void validate(RedisTopologySettings settings) {
    Objects.requireNonNull(settings);
  }

  public CredentialsProvider credentials(RedisTopologySettings settings) {
    return settings.credentials();
  }

  public Duration operationTimeout(RedisTopologySettings settings, Operation operation) {
    return settings.readTimeout();
  }

  public void validateUpdate(RedisTopologySettings old, RedisTopologySettings next) {
    if (old.mode() != next.mode() || !Objects.equals(old.masterName(), next.masterName()))
      throw new IllegalArgumentException("Topology identity changes require a new resource");
  }

  private static DefaultJedisClientConfig config(RedisTopologySettings s, Credentials credentials) {
    javax.net.ssl.SSLParameters ssl = new javax.net.ssl.SSLParameters();
    ssl.setEndpointIdentificationAlgorithm("HTTPS");
    return DefaultJedisClientConfig.builder()
        .serverDefaultProtocol()
        .ssl(s.seeds().iterator().next().tls())
        .sslParameters(ssl)
        .connectionTimeoutMillis(Math.toIntExact(s.connectTimeout().toMillis()))
        .socketTimeoutMillis(Math.toIntExact(s.readTimeout().toMillis()))
        .user(credentials.username().isEmpty() ? null : credentials.username())
        .password(credentials.passwordText())
        .build();
  }

  public UnifiedJedis create(RedisTopologySettings settings) {
    Set<HostAndPort> hosts = new HashSet<>();
    settings.seeds().forEach(seed -> hosts.add(new HostAndPort(seed.host(), seed.port())));
    var pool = new org.apache.commons.pool2.impl.GenericObjectPoolConfig<Connection>();
    pool.setMaxTotal(settings.maxConnections());
    pool.setMaxIdle(settings.maxConnections());
    pool.setMinIdle(0);
    pool.setMaxWait(settings.connectTimeout());
    try (Credentials data = settings.credentials().resolve()) {
      if (settings.mode() == RedisTopologySettings.Mode.CLUSTER)
        return RedisClusterClient.builder()
            .nodes(hosts)
            .clientConfig(config(settings, data))
            .poolConfig(pool)
            .maxAttempts(1)
            .maxTotalRetriesDuration(settings.readTimeout())
            .build();
      try (Credentials sentinel = settings.sentinelCredentials().resolve()) {
        return RedisSentinelClient.builder()
            .sentinels(hosts)
            .masterName(settings.masterName())
            .clientConfig(config(settings, data))
            .sentinelClientConfig(config(settings, sentinel))
            .poolConfig(pool)
            .build();
      }
    }
  }

  public void warmup(UnifiedJedis client, Duration timeout) {
    if (!"PONG".equals(client.ping())) throw new IllegalStateException("Redis readiness failed");
  }

  public void close(UnifiedJedis client) {
    client.close();
  }

  public ClientException.Kind classify(Throwable failure, Operation operation) {
    return new JedisAdapter().classify(failure, operation);
  }
}
