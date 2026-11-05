package io.github.samhom.bridle.rocketmq;

import static org.junit.jupiter.api.Assertions.*;

import io.github.samhom.bridle.core.*;
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
  void transactionSendRetainsLocalDecisionButRejectsNonFinalBrokerStatus() {
    class TransactionProducer extends TransactionMQProducer {
      SendStatus status = SendStatus.SEND_OK;
      LocalTransactionState decision = LocalTransactionState.UNKNOW;
      boolean missingResult;
      int sends;

      @Override
      public TransactionSendResult sendMessageInTransaction(Message message, Object argument) {
        sends++;
        if (missingResult) return null;
        TransactionSendResult result = new TransactionSendResult();
        result.setSendStatus(status);
        result.setLocalTransactionState(decision);
        return result;
      }
    }
    TransactionProducer nativeClient = new TransactionProducer();
    try (ClientRuntime runtime = new ClientRuntime()) {
      var handle =
          runtime.register(
              new ConfigSnapshot<>(new ResourceId("test", "test", "transaction"), 1, settings()),
              new FactoryAdapter<DefaultMQProducer, RocketMqSettings>(
                  "fixture", value -> nativeClient, (client, timeout) -> {}, client -> {}),
              ClientPolicy.defaults());
      ManagedProducer producer = new ManagedProducer(handle, Duration.ofSeconds(2));
      for (LocalTransactionState decision : LocalTransactionState.values()) {
        nativeClient.decision = decision;
        assertEquals(
            decision,
            producer.sendTransaction(new byte[] {1}, "id", null).getLocalTransactionState());
      }
      int before = nativeClient.sends;
      for (SendStatus status :
          List.of(
              SendStatus.FLUSH_DISK_TIMEOUT,
              SendStatus.FLUSH_SLAVE_TIMEOUT,
              SendStatus.SLAVE_NOT_AVAILABLE)) {
        nativeClient.status = status;
        ClientException error =
            assertThrows(
                ClientException.class, () -> producer.sendTransaction(new byte[] {1}, "id", null));
        assertEquals(ClientException.Kind.WRITE_OUTCOME_UNKNOWN, error.kind());
      }
      nativeClient.missingResult = true;
      assertEquals(
          ClientException.Kind.WRITE_OUTCOME_UNKNOWN,
          assertThrows(
                  ClientException.class, () -> producer.sendTransaction(new byte[] {1}, "id", null))
              .kind());
      assertEquals(before + 4, nativeClient.sends);
    }
  }

  @Test
  void rebalanceObserverCannotMutateNativeQueueIdentities() throws Exception {
    AtomicInteger observations = new AtomicInteger();
    var adapter =
        new RocketMqConsumerAdapter(
            (messages, context) -> ConsumeConcurrentlyStatus.CONSUME_SUCCESS,
            ConsumerOptions.defaults(),
            (topic, all, assigned) -> {
              observations.incrementAndGet();
              assertThrows(UnsupportedOperationException.class, all::clear);
              all.iterator().next().setQueueId(99);
              assigned.iterator().next().setBrokerName("changed");
            });
    DeliveryGuard closed =
        new DeliveryGuard() {
          public <T> T invoke(Callable<T> handler, T retry) {
            return retry;
          }
        };
    var consumer = adapter.createConsumer(settings(), closed);
    try {
      MessageQueue original = new MessageQueue("events", "broker", 1);
      Set<MessageQueue> nativeQueues = new HashSet<>(Set.of(original));
      consumer.getMessageQueueListener().messageQueueChanged("events", nativeQueues, nativeQueues);
      assertEquals(1, observations.get());
      assertEquals(1, original.getQueueId());
      assertEquals("broker", original.getBrokerName());
      assertTrue(nativeQueues.contains(new MessageQueue("events", "broker", 1)));
    } finally {
      consumer.shutdown();
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

  @Test
  void nullRebalanceQueuesStayEmptyAndABatchIsNotAckedAsOneDecision() throws Exception {
    AtomicInteger observations = new AtomicInteger();
    AtomicInteger deliveries = new AtomicInteger();
    var adapter =
        new RocketMqConsumerAdapter(
            (messages, context) -> {
              deliveries.incrementAndGet();
              return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            },
            ConsumerOptions.defaults(),
            (topic, all, assigned) -> {
              assertTrue(all.isEmpty());
              assertTrue(assigned.isEmpty());
              observations.incrementAndGet();
            });
    DeliveryGuard open =
        new DeliveryGuard() {
          public <T> T invoke(Callable<T> handler, T retry) {
            try {
              return handler.call();
            } catch (Exception failure) {
              return retry;
            }
          }
        };
    var consumer = adapter.createConsumer(settings(), open);
    try {
      consumer.getMessageQueueListener().messageQueueChanged("events", null, null);
      assertEquals(1, observations.get());
      var listener = (MessageListenerConcurrently) consumer.getMessageListener();
      MessageExt message = new MessageExt();
      message.setTopic("events");
      message.setBody(new byte[] {1});
      assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, listener.consumeMessage(null, null));
      assertEquals(
          ConsumeConcurrentlyStatus.RECONSUME_LATER,
          listener.consumeMessage(List.of(message, message), null));
      assertEquals(0, deliveries.get());
      assertEquals(
          ConsumeConcurrentlyStatus.CONSUME_SUCCESS,
          listener.consumeMessage(List.of(message), null));
      assertEquals(1, deliveries.get());
    } finally {
      consumer.shutdown();
    }
  }
}
