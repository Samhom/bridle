package io.github.samhom.bridle.redis;

import io.github.samhom.bridle.core.*;
import java.time.Duration;
import java.util.Objects;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

/**
 * Single-key commands share the managed absolute deadline with discovery, pool borrow and
 * redirects.
 */
public final class ManagedTopologyRedis {
  private static final Duration MIN_TTL = Duration.ofMillis(1);
  private static final Duration MAX_TTL = Duration.ofDays(3650);
  private final ManagedClient<UnifiedJedis, RedisTopologySettings> client;
  private final Duration timeout;
  private final int maxValueBytes;
  private final Operation getCommand;
  private final Operation setCommand;
  private final Operation incrementCommand;
  private final Operation deleteCommand;

  public ManagedTopologyRedis(
      ManagedClient<UnifiedJedis, RedisTopologySettings> client,
      Duration timeout,
      int maxValueBytes) {
    this.client = Objects.requireNonNull(client);
    this.timeout = Limits.timeout(timeout);
    this.maxValueBytes = Limits.count(maxValueBytes, 16 * 1024 * 1024);
    getCommand = Operation.read("get", this.timeout);
    setCommand = Operation.write("set", this.timeout);
    incrementCommand = Operation.write("incr", this.timeout);
    deleteCommand = Operation.write("del", this.timeout);
  }

  private static void key(String key) {
    if (key == null || Limits.exceedsUtf8(key, 4096))
      throw new IllegalArgumentException("Invalid Redis key size");
  }

  public String get(String key) {
    key(key);
    return client.execute(
        getCommand,
        (nativeClient, context) -> TopologyCallBudget.call(context, () -> nativeClient.get(key)));
  }

  public void set(String key, String value, Duration ttl) {
    key(key);
    Limits.utf8(value, maxValueBytes);
    if (ttl == null || ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0)
      throw new IllegalArgumentException("TTL out of bounds");
    client.execute(
        setCommand,
        (nativeClient, context) ->
            TopologyCallBudget.call(
                context,
                () -> nativeClient.set(key, value, SetParams.setParams().px(ttl.toMillis()))));
  }

  public long increment(String key) {
    key(key);
    return client.execute(
        incrementCommand,
        (nativeClient, context) -> TopologyCallBudget.call(context, () -> nativeClient.incr(key)));
  }

  public long delete(String key) {
    key(key);
    return client.execute(
        deleteCommand,
        (nativeClient, context) -> TopologyCallBudget.call(context, () -> nativeClient.del(key)));
  }

  public ManagedClient<UnifiedJedis, RedisTopologySettings> handle() {
    return client;
  }
}
