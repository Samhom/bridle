package io.github.samhom.bridle.settings;

import static io.github.samhom.bridle.core.UpdatePlan.Effect.*;

import io.github.samhom.bridle.core.*;

public final class SettingsUpdates {
  private SettingsUpdates() {}

  public static UpdatePlan plan(
      io.github.samhom.bridle.redis.RedisSettings a,
      io.github.samhom.bridle.redis.RedisSettings b) {
    return UpdatePlan.builder()
        .field("endpoint", a.endpoint(), b.endpoint(), REBUILD)
        .field("credentials", a.credentials(), b.credentials(), REBUILD)
        .field("connectTimeout", a.connectTimeout(), b.connectTimeout(), REBUILD)
        .field("readTimeout", a.readTimeout(), b.readTimeout(), REBUILD)
        .field("maxConnections", a.maxConnections(), b.maxConnections(), REBUILD)
        .field("database", a.database(), b.database(), FORBIDDEN)
        .field("maxValueBytes", a.maxValueBytes(), b.maxValueBytes(), LIVE)
        .build();
  }

  public static UpdatePlan plan(
      io.github.samhom.bridle.elasticsearch.ElasticsearchSettings a,
      io.github.samhom.bridle.elasticsearch.ElasticsearchSettings b) {
    return UpdatePlan.builder()
        .field("endpoint", a.endpoint(), b.endpoint(), REBUILD)
        .field("credentials", a.credentials(), b.credentials(), REBUILD)
        .field("connectTimeout", a.connectTimeout(), b.connectTimeout(), REBUILD)
        .field("readTimeout", a.readTimeout(), b.readTimeout(), REBUILD)
        .field("maxConnections", a.maxConnections(), b.maxConnections(), REBUILD)
        .field("maxBatchItems", a.maxBatchItems(), b.maxBatchItems(), LIVE)
        .field("maxDocumentBytes", a.maxDocumentBytes(), b.maxDocumentBytes(), LIVE)
        .build();
  }

  public static UpdatePlan plan(
      io.github.samhom.bridle.couchbase.CouchbaseSettings a,
      io.github.samhom.bridle.couchbase.CouchbaseSettings b) {
    return UpdatePlan.builder()
        .field("endpoint", a.endpoint(), b.endpoint(), REBUILD)
        .field("credentials", a.credentials(), b.credentials(), REBUILD)
        .field("connectTimeout", a.connectTimeout(), b.connectTimeout(), REBUILD)
        .field("operationTimeout", a.operationTimeout(), b.operationTimeout(), REBUILD)
        .field("bucket", a.bucket(), b.bucket(), FORBIDDEN)
        .field("scope", a.scope(), b.scope(), FORBIDDEN)
        .field("collection", a.collection(), b.collection(), FORBIDDEN)
        .field("maxDocumentBytes", a.maxDocumentBytes(), b.maxDocumentBytes(), LIVE)
        .build();
  }

  public static UpdatePlan plan(
      io.github.samhom.bridle.rocketmq.RocketMqSettings a,
      io.github.samhom.bridle.rocketmq.RocketMqSettings b) {
    return UpdatePlan.builder()
        .field("nameServer", a.nameServer(), b.nameServer(), REBUILD)
        .field("credentials", a.credentials(), b.credentials(), REBUILD)
        .field("group", a.group(), b.group(), FORBIDDEN)
        .field("topic", a.topic(), b.topic(), FORBIDDEN)
        .field("requestTimeout", a.requestTimeout(), b.requestTimeout(), REBUILD)
        .field("consumerThreads", a.consumerThreads(), b.consumerThreads(), REBUILD)
        .field("maxMessageBytes", a.maxMessageBytes(), b.maxMessageBytes(), REBUILD)
        .build();
  }
}
