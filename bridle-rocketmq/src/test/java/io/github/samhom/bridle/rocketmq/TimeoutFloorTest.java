package io.github.samhom.bridle.rocketmq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.samhom.bridle.core.ClientException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TimeoutFloorTest {
  @Test
  void sendBudgetFloorsInsteadOfStretchingASubMillisecondRemainder() {
    assertEquals(1, ManagedProducer.sendTimeoutMillis(Duration.ofNanos(1_500_000)));
    assertEquals(2, ManagedProducer.sendTimeoutMillis(Duration.ofNanos(2_000_000)));
    assertEquals(
        ClientException.Kind.TIMEOUT,
        assertThrows(
                ClientException.class,
                () -> ManagedProducer.sendTimeoutMillis(Duration.ofNanos(999_999)))
            .kind());
  }

  @Test
  void shutdownWaitFloorsAndStillAvoidsAZeroTimeout() {
    assertEquals(1L, RocketMqConsumerAdapter.shutdownMillis(Duration.ofNanos(1_500_000)));
    assertEquals(1L, RocketMqConsumerAdapter.shutdownMillis(Duration.ofNanos(999_999)));
    assertEquals(2L, RocketMqConsumerAdapter.shutdownMillis(Duration.ofNanos(2_000_000)));
  }
}
