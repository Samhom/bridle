package io.github.samhom.bridle.rocketmq;

import io.github.samhom.bridle.core.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.message.Message;

public final class ManagedProducer {
  private final ManagedClient<DefaultMQProducer, RocketMqSettings> client;
  private final Duration timeout;

  public ManagedProducer(
      ManagedClient<DefaultMQProducer, RocketMqSettings> client, Duration timeout) {
    this.client = java.util.Objects.requireNonNull(client);
    this.timeout = Limits.timeout(timeout);
  }

  public record Outbound(byte[] body, String businessKey) {
    public Outbound {
      Limits.bytes(body, 4 * 1024 * 1024);
      Limits.messageKey(businessKey);
      body = body.clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }

    @Override
    public String toString() {
      return "Outbound{redacted}";
    }
  }

  public CompletableFuture<SendResult> send(byte[] body, String businessKey) {
    return sendOne(new Outbound(body, businessKey), 0, null);
  }

  /**
   * Delay levels refer to the broker's configured schedule; not a wall-clock delivery guarantee.
   */
  public CompletableFuture<SendResult> sendDelayed(byte[] body, String businessKey, int level) {
    if (level < 1 || level > 18) throw new IllegalArgumentException("Delay level out of bounds");
    return sendOne(new Outbound(body, businessKey), level, null);
  }

  /** Stable routing while the queue set is stable. Callers must await sends per ordering key. */
  public CompletableFuture<SendResult> sendOrdered(
      byte[] body, String businessKey, String orderingKey) {
    Limits.messageKey(orderingKey);
    return sendOne(new Outbound(body, businessKey), 0, orderingKey);
  }

  private CompletableFuture<SendResult> sendOne(Outbound outbound, int delay, String orderingKey) {
    return client.executeAsync(
        Operation.write(orderingKey == null ? "send" : "send.ordered", timeout),
        (producer, context) -> {
          RocketMqSettings settings = context.settings(RocketMqSettings.class);
          Message message = message(outbound, settings);
          if (delay > 0) message.setDelayTimeLevel(delay);
          CompletableFuture<SendResult> completion = new CompletableFuture<>();
          SendCallback callback = callback(completion);
          long remaining = sendTimeoutMillis(context.remaining());
          if (orderingKey == null) producer.send(message, callback, remaining);
          else
            producer.send(
                message,
                (queues, msg, key) -> {
                  var sorted = new java.util.ArrayList<>(queues);
                  sorted.sort(
                      java.util.Comparator.comparing(
                              org.apache.rocketmq.common.message.MessageQueue::getBrokerName)
                          .thenComparingInt(
                              org.apache.rocketmq.common.message.MessageQueue::getQueueId));
                  if (sorted.isEmpty()) throw new IllegalStateException("No writable queues");
                  return sorted.get(Math.floorMod(key.hashCode(), sorted.size()));
                },
                orderingKey,
                callback,
                remaining);
          return completion;
        });
  }

  public CompletableFuture<SendResult> sendBatch(java.util.List<Outbound> values) {
    Limits.count(values.size(), 256);
    // Bound before defensively cloning bodies, then recheck against current generation settings.
    long total = 0;
    for (Outbound value : values) {
      total += value.body.length;
      if (total > 4 * 1024 * 1024) throw new IllegalArgumentException("Batch byte budget exceeded");
    }
    var copy = java.util.List.copyOf(values);
    return client.executeAsync(
        Operation.write("send.batch", timeout),
        (producer, context) -> {
          var settings = context.settings(RocketMqSettings.class);
          var messages = new java.util.ArrayList<Message>();
          for (Outbound value : copy) messages.add(message(value, settings));
          byte[] encoded =
              org.apache.rocketmq.common.message.MessageBatch.generateFromList(messages).encode();
          Limits.bytes(encoded, settings.maxMessageBytes());
          context.checkDeadline();
          CompletableFuture<SendResult> completion = new CompletableFuture<>();
          producer.send(messages, callback(completion), sendTimeoutMillis(context.remaining()));
          return completion;
        });
  }

  /**
   * A timeout may leave the transaction outcome unknown; the broker listener resolves persisted
   * decisions.
   */
  public TransactionSendResult sendTransaction(byte[] body, String businessKey, Object argument) {
    Outbound value = new Outbound(body, businessKey);
    return client.execute(
        Operation.write("send.transaction", timeout),
        (producer, context) -> {
          if (!(producer instanceof TransactionMQProducer transactional))
            throw new IllegalStateException(
                "Register a transaction-enabled producer adapter first");
          context.checkDeadline();
          TransactionSendResult result =
              transactional.sendMessageInTransaction(
                  message(value, context.settings(RocketMqSettings.class)), argument);
          if (result == null || result.getSendStatus() != SendStatus.SEND_OK)
            throw new ClientException(
                ClientException.Kind.WRITE_OUTCOME_UNKNOWN,
                "Broker returned a non-final transaction send status");
          return result;
        });
  }

  /**
   * Floors the remaining send budget. A sub-millisecond remainder is a timeout, not one extra
   * millisecond.
   */
  static int sendTimeoutMillis(Duration remaining) {
    return CallContext.millisWithin(remaining.toNanos());
  }

  private static Message message(Outbound value, RocketMqSettings settings) {
    Limits.bytes(value.body, settings.maxMessageBytes());
    Message message = new Message(settings.topic(), value.body.clone());
    message.setKeys(value.businessKey);
    return message;
  }

  private static SendCallback callback(CompletableFuture<SendResult> completion) {
    return new SendCallback() {
      public void onSuccess(SendResult result) {
        if (result != null && result.getSendStatus() == SendStatus.SEND_OK)
          completion.complete(result);
        else
          completion.completeExceptionally(
              new ClientException(
                  ClientException.Kind.WRITE_OUTCOME_UNKNOWN,
                  "Broker returned a non-final send status"));
      }

      public void onException(Throwable error) {
        completion.completeExceptionally(error);
      }
    };
  }

  public ManagedClient<DefaultMQProducer, RocketMqSettings> handle() {
    return client;
  }
}
