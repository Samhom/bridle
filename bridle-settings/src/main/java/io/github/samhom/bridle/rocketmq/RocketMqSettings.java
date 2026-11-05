package io.github.samhom.bridle.rocketmq;

import io.github.samhom.bridle.core.*;
import java.time.Duration;
import java.util.Objects;

public record RocketMqSettings(
    Endpoint nameServer,
    CredentialsProvider credentials,
    String group,
    String topic,
    Duration requestTimeout,
    int consumerThreads,
    int maxMessageBytes)
    implements ImmutableSettings {
  public RocketMqSettings {
    Objects.requireNonNull(nameServer);
    Objects.requireNonNull(credentials);
    Limits.name(group);
    if (topic != null && topic.startsWith("%DLQ%")) Limits.name(topic.substring(5));
    else Limits.name(topic);
    Limits.timeout(requestTimeout);
    Limits.count(consumerThreads, 256);
    Limits.count(maxMessageBytes, 4 * 1024 * 1024);
  }

  public static String deadLetterTopic(String sourceGroup) {
    return "%DLQ%" + Limits.name(sourceGroup);
  }

  public static RocketMqSettings defaults(
      Endpoint nameServer, CredentialsProvider credentials, String group, String topic) {
    return new RocketMqSettings(
        nameServer, credentials, group, topic, Duration.ofSeconds(3), 8, 1024 * 1024);
  }

  @Override
  public String toString() {
    return "RocketMqSettings{redacted}";
  }
}
