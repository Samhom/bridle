package io.github.samhom.driverkit.redis;

import io.github.samhom.driverkit.core.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

/**
 * Single-key commands share the managed absolute deadline with discovery, pool borrow and
 * redirects.
 */
public final class ManagedTopologyRedis {
  private final ManagedClient<UnifiedJedis, RedisTopologySettings> client;
  private final Duration timeout;
  private final int maxValueBytes;

  public ManagedTopologyRedis(
      ManagedClient<UnifiedJedis, RedisTopologySettings> client,
      Duration timeout,
      int maxValueBytes) {
    this.client = Objects.requireNonNull(client);
    this.timeout = Limits.timeout(timeout);
    this.maxValueBytes = Limits.count(maxValueBytes, 16 * 1024 * 1024);
  }

  private static void key(String key) {
    if (key == null || key.getBytes(StandardCharsets.UTF_8).length > 4096)
      throw new IllegalArgumentException("Invalid Redis key size");
  }

  public String get(String key) {
    key(key);
    return client.execute(
        Operation.read("get", timeout),
        (nativeClient, context) -> TopologyCallBudget.call(context, () -> nativeClient.get(key)));
  }

  public void set(String key, String value, Duration ttl) {
    key(key);
    Limits.bytes(value == null ? null : value.getBytes(StandardCharsets.UTF_8), maxValueBytes);
    if (ttl == null
        || ttl.compareTo(Duration.ofMillis(1)) < 0
        || ttl.compareTo(Duration.ofDays(3650)) > 0)
      throw new IllegalArgumentException("TTL out of bounds");
    client.execute(
        Operation.write("set", timeout),
        (nativeClient, context) ->
            TopologyCallBudget.call(
                context,
                () -> nativeClient.set(key, value, SetParams.setParams().px(ttl.toMillis()))));
  }

  public long increment(String key) {
    key(key);
    return client.execute(
        Operation.write("incr", timeout),
        (nativeClient, context) -> TopologyCallBudget.call(context, () -> nativeClient.incr(key)));
  }

  public long delete(String key) {
    key(key);
    return client.execute(
        Operation.write("del", timeout),
        (nativeClient, context) -> TopologyCallBudget.call(context, () -> nativeClient.del(key)));
  }

  public ManagedClient<UnifiedJedis, RedisTopologySettings> handle() {
    return client;
  }
}
