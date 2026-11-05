package io.github.samhom.bridle.rocketmq.grpc;

import io.github.samhom.bridle.core.ImmutableSettings;
import io.github.samhom.bridle.core.Limits;
import java.util.Objects;

public record GrpcConsumerSettings(
    GrpcSettings client, String group, int threads, int maxCachedMessages, int maxCachedBytes)
    implements ImmutableSettings {
  public GrpcConsumerSettings {
    Objects.requireNonNull(client);
    Limits.name(group);
    Limits.count(threads, 256);
    Limits.count(maxCachedMessages, 100000);
    Limits.count(maxCachedBytes, 256 * 1024 * 1024);
  }
}
