package io.github.samhom.bridle.redis;

import io.github.samhom.bridle.core.*;
import java.time.Duration;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.params.SetParams;

public final class ManagedRedis {
  private final ManagedClient<RedisClient, RedisSettings> client;
  private final Duration timeout;
  private final int maxValueBytes;

  public ManagedRedis(
      ManagedClient<RedisClient, RedisSettings> client, Duration timeout, int maxValueBytes) {
    this.client = java.util.Objects.requireNonNull(client);
    this.timeout = Limits.timeout(timeout);
    this.maxValueBytes = Limits.count(maxValueBytes, 16 * 1024 * 1024);
  }

  private static void key(String key) {
    if (key == null || Limits.exceedsUtf8(key, 4096))
      throw new IllegalArgumentException("Invalid Redis key size");
  }

  public String get(String key) {
    key(key);
    return client.execute(
        Operation.read("get", timeout),
        (nativeClient, context) ->
            RedisCallBudget.execute(nativeClient, context, jedis -> jedis.get(key)));
  }

  public void set(String key, String value, Duration ttl) {
    key(key);
    Limits.utf8(value, maxValueBytes);
    if (ttl == null
        || ttl.compareTo(Duration.ofMillis(1)) < 0
        || ttl.compareTo(Duration.ofDays(3650)) > 0)
      throw new IllegalArgumentException("TTL out of bounds");
    client.execute(
        Operation.write("set", timeout),
        (nativeClient, context) -> {
          Limits.utf8(
              value,
              Math.min(maxValueBytes, context.settings(RedisSettings.class).maxValueBytes()));
          return RedisCallBudget.execute(
              nativeClient,
              context,
              jedis -> jedis.set(key, value, SetParams.setParams().px(ttl.toMillis())));
        });
  }

  public long increment(String key) {
    key(key);
    return client.execute(
        Operation.write("incr", timeout),
        (nativeClient, context) ->
            RedisCallBudget.execute(nativeClient, context, jedis -> jedis.incr(key)));
  }

  public long delete(String key) {
    key(key);
    return client.execute(
        Operation.write("del", timeout),
        (nativeClient, context) ->
            RedisCallBudget.execute(nativeClient, context, jedis -> jedis.del(key)));
  }

  /** Bounded single-connection batch; responses preserve input order. */
  public java.util.List<String> getAll(java.util.List<String> keys) {
    java.util.List<String> copy = java.util.List.copyOf(keys);
    Limits.count(copy.size(), 1000);
    copy.forEach(ManagedRedis::key);
    return client.execute(
        Operation.read("mget", timeout),
        (nativeClient, context) ->
            RedisCallBudget.execute(
                nativeClient, context, jedis -> jedis.mget(copy.toArray(String[]::new))));
  }

  /** Trusted, deployment-owned script only. No automatic replay of potentially mutating scripts. */
  public Object eval(String script, java.util.List<String> keys, java.util.List<String> arguments) {
    Limits.utf8(script, 65536);
    java.util.List<String> keyCopy = java.util.List.copyOf(keys),
        args = java.util.List.copyOf(arguments);
    Limits.count(keyCopy.size(), 1000);
    Limits.count(Math.max(1, args.size()), 1000);
    keyCopy.forEach(ManagedRedis::key);
    long total = 0;
    for (String arg : args) {
      long length = Limits.utf8Bytes(arg);
      if (total > maxValueBytes - length)
        throw new IllegalArgumentException("Script argument budget exceeded");
      total += length;
    }
    final long bytes = total;
    return client.execute(
        Operation.write("eval", timeout),
        (nativeClient, context) -> {
          if (bytes > context.settings(RedisSettings.class).maxValueBytes())
            throw new IllegalArgumentException("Script argument budget exceeded");
          return RedisCallBudget.execute(
              nativeClient, context, jedis -> jedis.eval(script, keyCopy, args));
        });
  }

  /** Fixed command batch avoids exposing an unbounded pipeline callback. */
  public java.util.List<Object> setBatch(
      java.util.Map<String, String> values, boolean transaction) {
    java.util.Map<String, String> copy = java.util.Map.copyOf(values);
    Limits.count(copy.size(), 1000);
    long bytes = 0;
    for (var entry : copy.entrySet()) {
      key(entry.getKey());
      long length = Limits.utf8Bytes(entry.getValue());
      if (length > maxValueBytes)
        throw new IllegalArgumentException("Payload is missing or oversized");
      bytes += length;
    }
    if (bytes > 16 * 1024 * 1024) throw new IllegalArgumentException("Batch byte budget exceeded");
    return client.execute(
        Operation.write(transaction ? "transaction.set" : "pipeline.set", timeout),
        (nativeClient, context) ->
            RedisCallBudget.execute(
                nativeClient,
                context,
                jedis -> {
                  for (String value : copy.values())
                    Limits.utf8(
                        value,
                        Math.min(
                            maxValueBytes, context.settings(RedisSettings.class).maxValueBytes()));
                  context.checkDeadline();
                  if (transaction) {
                    try (redis.clients.jedis.Transaction tx = jedis.multi()) {
                      copy.forEach(tx::set);
                      return BatchOutcomes.transaction(tx.exec());
                    }
                  }
                  try (redis.clients.jedis.Pipeline pipeline = jedis.pipelined()) {
                    copy.forEach(pipeline::set);
                    return BatchOutcomes.pipeline(pipeline.syncAndReturnAll());
                  }
                }));
  }

  public ManagedClient<RedisClient, RedisSettings> handle() {
    return client;
  }
}
