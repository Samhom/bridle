package io.github.samhom.driverkit.rocketmq;

import java.util.Objects;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;

/** First-start position applies only when the broker has no committed group offset. */
public record ConsumerOptions(int maxReconsumeTimes, ConsumeFromWhere firstStart) {
  public ConsumerOptions {
    if (maxReconsumeTimes < 0 || maxReconsumeTimes > 100)
      throw new IllegalArgumentException("Retry limit out of bounds");
    Objects.requireNonNull(firstStart);
    if (firstStart != ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET
        && firstStart != ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET)
      throw new IllegalArgumentException("Use broker offset tools for timestamp reset");
  }

  public static ConsumerOptions defaults() {
    return new ConsumerOptions(16, ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
  }
}
