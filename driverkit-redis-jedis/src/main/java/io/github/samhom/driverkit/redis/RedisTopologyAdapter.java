package io.github.samhom.driverkit.redis;

import io.github.samhom.driverkit.core.*;
import java.time.*;
import java.util.*;
import redis.clients.jedis.*;

/**
 * Owns native discovery and routing; only explicit server redirections can repeat a dispatched
 * command.
 */
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

  private static JedisClientConfig config(RedisTopologySettings settings, Credentials credentials) {
    javax.net.ssl.SSLParameters ssl = new javax.net.ssl.SSLParameters();
    ssl.setEndpointIdentificationAlgorithm("HTTPS");
    DefaultJedisClientConfig fixed =
        DefaultJedisClientConfig.builder()
            .serverDefaultProtocol()
            .ssl(settings.seeds().iterator().next().tls())
            .sslParameters(ssl)
            .user(credentials.username().isEmpty() ? null : credentials.username())
            .password(credentials.passwordText())
            .build();
    return new JedisClientConfig() {
      public RedisProtocol getRedisProtocol() {
        return fixed.getRedisProtocol();
      }

      public boolean isAutoNegotiateProtocol() {
        return fixed.isAutoNegotiateProtocol();
      }

      public int getConnectionTimeoutMillis() {
        return TopologyCallBudget.millis(settings.connectTimeout());
      }

      public int getSocketTimeoutMillis() {
        return TopologyCallBudget.millis(settings.readTimeout());
      }

      public int getBlockingSocketTimeoutMillis() {
        return getSocketTimeoutMillis();
      }

      public String getUser() {
        return fixed.getUser();
      }

      public String getPassword() {
        return fixed.getPassword();
      }

      public java.util.function.Supplier<RedisCredentials> getCredentialsProvider() {
        return fixed.getCredentialsProvider();
      }

      public boolean isSsl() {
        return fixed.isSsl();
      }

      public javax.net.ssl.SSLParameters getSslParameters() {
        return fixed.getSslParameters();
      }

      public ClientSetInfoConfig getClientSetInfoConfig() {
        return fixed.getClientSetInfoConfig();
      }
    };
  }

  private static long version(CredentialsProvider provider) {
    return provider instanceof CredentialSource source ? source.state().version() : 0;
  }

  private static void check(CredentialsProvider provider, long expected) {
    if (!(provider instanceof CredentialSource source)) return;
    CredentialState state = source.state();
    if (state.revoked() || !Instant.now().isBefore(state.expiresAt()))
      throw new ClientException(
          ClientException.Kind.REVOKED, "Topology credentials are revoked or expired");
    if (state.version() != expected)
      throw new ClientException(
          ClientException.Kind.UNAVAILABLE,
          "Topology credential version requires generation replacement");
  }

  public UnifiedJedis create(RedisTopologySettings settings) throws Exception {
    return create(settings, settings.connectTimeout());
  }

  @Override
  public UnifiedJedis create(RedisTopologySettings settings, Duration remaining) throws Exception {
    return TopologyCallBudget.call(remaining, () -> createWithinBudget(settings));
  }

  private UnifiedJedis createWithinBudget(RedisTopologySettings settings) {
    validate(settings);
    Set<HostAndPort> hosts = new HashSet<>();
    settings.seeds().forEach(seed -> hosts.add(new HostAndPort(seed.host(), seed.port())));
    var pool = new org.apache.commons.pool2.impl.GenericObjectPoolConfig<Connection>();
    pool.setMaxTotal(settings.maxConnections());
    pool.setMaxIdle(settings.maxConnections());
    pool.setMinIdle(0);
    pool.setMaxWait(settings.connectTimeout());
    long dataVersion = version(settings.credentials()),
        sentinelVersion = version(settings.sentinelCredentials());
    Runnable credentials =
        () -> {
          check(settings.credentials(), dataVersion);
          if (settings.mode() == RedisTopologySettings.Mode.SENTINEL)
            check(settings.sentinelCredentials(), sentinelVersion);
        };
    try (Credentials data = settings.credentials().resolve()) {
      JedisClientConfig dataConfig = config(settings, data);
      if (settings.mode() == RedisTopologySettings.Mode.CLUSTER) {
        var provider =
            new TopologyConnections.Cluster(
                hosts, dataConfig, pool, settings.connectTimeout(), settings.readTimeout());
        try {
          return RedisClusterClient.builder()
              .nodes(hosts)
              .clientConfig(dataConfig)
              .connectionProvider(provider)
              .commandExecutor(TopologyConnections.clusterExecutor(provider, settings, credentials))
              .build();
        } catch (RuntimeException | Error failure) {
          provider.close();
          throw failure;
        }
      }
      try (Credentials sentinel = settings.sentinelCredentials().resolve()) {
        JedisClientConfig sentinelConfig = config(settings, sentinel);
        var provider =
            new TopologyConnections.Sentinel(
                settings.masterName(),
                dataConfig,
                pool,
                hosts,
                sentinelConfig,
                settings.connectTimeout());
        try {
          return RedisSentinelClient.builder()
              .masterName(settings.masterName())
              .sentinels(hosts)
              .clientConfig(dataConfig)
              .sentinelClientConfig(sentinelConfig)
              .connectionProvider(provider)
              .commandExecutor(
                  TopologyConnections.sentinelExecutor(provider, settings, credentials))
              .build();
        } catch (RuntimeException | Error failure) {
          provider.close();
          throw failure;
        }
      }
    }
  }

  public void warmup(UnifiedJedis client, Duration timeout) throws Exception {
    TopologyCallBudget.call(
        timeout,
        () -> {
          if (!"PONG".equals(client.ping()))
            throw new IllegalStateException("Redis readiness failed");
          return null;
        });
  }

  public void close(UnifiedJedis client) {
    client.close();
  }

  public ClientException.Kind classify(Throwable failure, Operation operation) {
    failure = FailureClassifier.unwrap(failure);
    if (failure instanceof TopologyConnections.AfterDispatchFailure && failure.getCause() != null)
      failure = failure.getCause();
    return new JedisAdapter().classify(failure, operation);
  }
}
