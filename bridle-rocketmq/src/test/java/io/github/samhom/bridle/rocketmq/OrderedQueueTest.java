package io.github.samhom.bridle.rocketmq;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.apache.rocketmq.common.message.MessageQueue;
import org.junit.jupiter.api.Test;

class OrderedQueueTest {
  @Test
  void orderedSelectionDoesNotRequireAMutableCopy() {
    MessageQueue first = new MessageQueue("events", "a", 0);
    MessageQueue second = new MessageQueue("events", "b", 1);
    List<MessageQueue> sorted = List.of(first, second);
    List<MessageQueue> reversed = List.of(second, first);
    MessageQueue expected = sorted.get(Math.floorMod("order".hashCode(), sorted.size()));
    assertSame(expected, ManagedProducer.orderedQueue(sorted, "order"));
    assertSame(expected, ManagedProducer.orderedQueue(reversed, "order"));
    assertThrows(
        IllegalStateException.class, () -> ManagedProducer.orderedQueue(List.of(), "order"));
  }
}
