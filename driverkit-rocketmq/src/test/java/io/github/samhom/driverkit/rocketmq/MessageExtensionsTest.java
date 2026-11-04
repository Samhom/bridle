package io.github.samhom.driverkit.rocketmq;

import static org.junit.jupiter.api.Assertions.*;

import io.github.samhom.driverkit.core.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.*;
import org.junit.jupiter.api.Test;

class MessageExtensionsTest {
  static RocketMqSettings settings() {
    return RocketMqSettings.defaults(
        new Endpoint("localhost", 9876, false),
        () -> new Credentials("fixture", new char[0]),
        "producer",
        "events");
  }

  static class Producer extends DefaultMQProducer {
    Message last;
    MessageQueue selected;
    int batches;
    SendStatus status = SendStatus.SEND_OK;

    void complete(SendCallback callback) {
      SendResult result = new SendResult();
      result.setSendStatus(status);
      callback.onSuccess(result);
    }

    @Override
    public void send(Message m, SendCallback callback, long timeout) {
      last = m;
      complete(callback);
    }

    @Override
    public void send(
        Message m, MessageQueueSelector selector, Object key, SendCallback callback, long timeout) {
      last = m;
      selected =
          selector.select(
              List.of(new MessageQueue("events", "b", 1), new MessageQueue("events", "a", 0)),
              m,
              key);
      complete(callback);
    }

    @Override
    public void send(Collection<Message> messages, SendCallback callback, long timeout) {
      batches++;
      complete(callback);
    }
  }

  @Test
  void routesOrderedMessagesSnapshotsPayloadAndRetainsUnknownWriteStatus() throws Exception {
    Producer nativeClient = new Producer();
    try (ClientRuntime runtime = new ClientRuntime()) {
      var handle =
          runtime.register(
              new ConfigSnapshot<>(new ResourceId("test", "test", "mq"), 1, settings()),
              new FactoryAdapter<DefaultMQProducer, RocketMqSettings>(
                  "fixture", s -> nativeClient, (p, t) -> {}, p -> {}),
              ClientPolicy.defaults());
      var producer = new ManagedProducer(handle, Duration.ofSeconds(2));
      byte[] payload = {1};
      var outbound = new ManagedProducer.Outbound(payload, "id");
      payload[0] = 9;
      assertEquals(1, outbound.body()[0]);
      outbound.body()[0] = 8;
      assertEquals(1, outbound.body()[0]);
      producer.sendOrdered(payload, "id", "order").get(2, TimeUnit.SECONDS);
      MessageQueue first = nativeClient.selected;
      producer.sendOrdered(payload, "id2", "order").get(2, TimeUnit.SECONDS);
      assertEquals(first, nativeClient.selected);
      producer.sendDelayed(payload, "id", 3).get(2, TimeUnit.SECONDS);
      assertEquals(3, nativeClient.last.getDelayTimeLevel());
      producer.sendBatch(List.of(outbound, outbound)).get(2, TimeUnit.SECONDS);
      assertEquals(1, nativeClient.batches);
      nativeClient.status = SendStatus.FLUSH_DISK_TIMEOUT;
      var error =
          assertThrows(
              ExecutionException.class,
              () -> producer.send(payload, "id").get(2, TimeUnit.SECONDS));
      assertEquals(
          ClientException.Kind.WRITE_OUTCOME_UNKNOWN, ((ClientException) error.getCause()).kind());
      assertThrows(ClientException.class, () -> producer.sendTransaction(payload, "id", null));
    }
  }

  @Test
  void orderedConsumerGuardPreventsAckAfterAdmissionCloses() throws Exception {
    AtomicBoolean open = new AtomicBoolean(true);
    AtomicInteger deliveries = new AtomicInteger();
    DeliveryGuard guard =
        new DeliveryGuard() {
          public <T> T invoke(Callable<T> handler, T retry) {
            if (!open.get()) return retry;
            try {
              return handler.call();
            } catch (Exception e) {
              return retry;
            }
          }
        };
    var adapter =
        RocketMqConsumerAdapter.ordered(
            (messages, context) -> {
              deliveries.incrementAndGet();
              return ConsumeOrderlyStatus.SUCCESS;
            },
            new ConsumerOptions(3, ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET),
            null);
    var consumer = adapter.createConsumer(settings(), guard);
    try {
      assertEquals(3, consumer.getMaxReconsumeTimes());
      assertEquals(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET, consumer.getConsumeFromWhere());
      var listener = (MessageListenerOrderly) consumer.getMessageListener();
      MessageExt message = new MessageExt();
      message.setTopic("events");
      message.setBody(new byte[] {1});
      assertEquals(ConsumeOrderlyStatus.SUCCESS, listener.consumeMessage(List.of(message), null));
      open.set(false);
      assertEquals(
          ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT,
          listener.consumeMessage(List.of(message), null));
      assertEquals(1, deliveries.get());
      assertEquals("%DLQ%source", RocketMqSettings.deadLetterTopic("source"));
    } finally {
      consumer.shutdown();
    }
  }
}
