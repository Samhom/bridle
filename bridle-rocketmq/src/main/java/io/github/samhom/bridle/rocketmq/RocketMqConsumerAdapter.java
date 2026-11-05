package io.github.samhom.bridle.rocketmq;

import io.github.samhom.bridle.core.*;
import java.util.Objects;
import java.util.UUID;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.*;

/** The native SDK owns broker retry, acknowledgment and offset persistence. */
public final class RocketMqConsumerAdapter
    implements ConsumerAdapter<DefaultMQPushConsumer, RocketMqSettings> {
  private final MessageListenerConcurrently handler;
  private final MessageListenerOrderly ordered;
  private final ConsumerOptions options;
  private final org.apache.rocketmq.client.consumer.MessageQueueListener rebalance;

  public RocketMqConsumerAdapter(MessageListenerConcurrently handler) {
    this(handler, ConsumerOptions.defaults(), null);
  }

  public RocketMqConsumerAdapter(
      MessageListenerConcurrently handler,
      ConsumerOptions options,
      org.apache.rocketmq.client.consumer.MessageQueueListener rebalance) {
    this.handler = Objects.requireNonNull(handler);
    this.ordered = null;
    this.options = Objects.requireNonNull(options);
    this.rebalance = rebalance;
  }

  private RocketMqConsumerAdapter(
      MessageListenerOrderly handler,
      ConsumerOptions options,
      org.apache.rocketmq.client.consumer.MessageQueueListener rebalance) {
    this.handler = null;
    this.ordered = Objects.requireNonNull(handler);
    this.options = Objects.requireNonNull(options);
    this.rebalance = rebalance;
  }

  public static RocketMqConsumerAdapter ordered(
      MessageListenerOrderly handler,
      ConsumerOptions options,
      org.apache.rocketmq.client.consumer.MessageQueueListener rebalance) {
    return new RocketMqConsumerAdapter(handler, options, rebalance);
  }

  @Override
  public String id() {
    return "rocketmq-remoting5-consumer";
  }

  @Override
  public void validate(RocketMqSettings settings) {
    RemotingAuthentication.validateTransport(settings);
  }

  @Override
  public void validateUpdate(RocketMqSettings previous, RocketMqSettings next) {
    RemotingAuthentication.validateUpdate(previous, next);
  }

  @Override
  public DefaultMQPushConsumer start(RocketMqSettings settings, DeliveryGuard guard)
      throws Exception {
    DefaultMQPushConsumer consumer = createConsumer(settings, guard);
    try {
      consumer.start();
      return consumer;
    } catch (Exception failure) {
      consumer.shutdown();
      throw failure;
    }
  }

  DefaultMQPushConsumer createConsumer(RocketMqSettings settings, DeliveryGuard guard)
      throws Exception {
    validate(settings);
    DefaultMQPushConsumer consumer =
        new DefaultMQPushConsumer(
            settings.group(), RemotingAuthentication.hook(settings.credentials()));
    consumer.setNamesrvAddr(settings.nameServer().authority());
    consumer.setUseTLS(settings.nameServer().tls());
    consumer.setInstanceName("bridle-" + UUID.randomUUID());
    consumer.setMqClientApiTimeout(Math.toIntExact(settings.requestTimeout().toMillis()));
    consumer.setConsumeThreadMin(settings.consumerThreads());
    consumer.setConsumeThreadMax(settings.consumerThreads());
    consumer.setConsumeMessageBatchMaxSize(1);
    consumer.setPullBatchSize(16);
    consumer.setPullThresholdForQueue(256);
    consumer.setPullThresholdSizeForQueue(16);
    consumer.setMaxReconsumeTimes(options.maxReconsumeTimes());
    consumer.setConsumeFromWhere(options.firstStart());
    if (rebalance != null)
      consumer.setMessageQueueListener(
          (topic, all, assigned) -> {
            try {
              rebalance.messageQueueChanged(topic, queueSnapshot(all), queueSnapshot(assigned));
            } catch (RuntimeException ignored) {
              /* observational callback must not stop native rebalancing */
            }
          });
    consumer.setAwaitTerminationMillisWhenShutdown(settings.requestTimeout().toMillis());
    consumer.subscribe(settings.topic(), "*");
    if (ordered == null)
      consumer.registerMessageListener(
          (MessageListenerConcurrently)
              (messages, context) ->
                  guard.invoke(
                      () -> {
                        // Native ack is one status for the whole list. A larger list would apply
                        // one decision to messages the handler did not see.
                        if (messages == null || messages.size() != 1)
                          return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        org.apache.rocketmq.common.message.MessageExt message = messages.get(0);
                        if (message == null || !settings.topic().equals(message.getTopic()))
                          return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        Limits.bytes(message.getBody(), settings.maxMessageBytes());
                        return Objects.requireNonNull(handler.consumeMessage(messages, context));
                      },
                      ConsumeConcurrentlyStatus.RECONSUME_LATER));
    else
      consumer.registerMessageListener(
          (MessageListenerOrderly)
              (messages, context) ->
                  guard.invoke(
                      () -> {
                        if (messages == null || messages.size() != 1)
                          return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
                        var message = messages.get(0);
                        if (message == null || !settings.topic().equals(message.getTopic()))
                          return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
                        Limits.bytes(message.getBody(), settings.maxMessageBytes());
                        return Objects.requireNonNull(ordered.consumeMessage(messages, context));
                      },
                      ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT));
    return consumer;
  }

  private static java.util.Set<org.apache.rocketmq.common.message.MessageQueue> queueSnapshot(
      java.util.Set<org.apache.rocketmq.common.message.MessageQueue> queues) {
    if (queues == null || queues.isEmpty()) return java.util.Set.of();
    java.util.Set<org.apache.rocketmq.common.message.MessageQueue> copy = new java.util.HashSet<>();
    for (var queue : queues) {
      if (queue == null) continue;
      copy.add(
          new org.apache.rocketmq.common.message.MessageQueue(
              queue.getTopic(), queue.getBrokerName(), queue.getQueueId()));
    }
    return java.util.Set.copyOf(copy);
  }

  @Override
  public void pause(DefaultMQPushConsumer consumer) {
    consumer.suspend();
  }

  @Override
  public void resume(DefaultMQPushConsumer consumer) {
    consumer.resume();
  }

  @Override
  public void close(DefaultMQPushConsumer consumer) {
    consumer.shutdown();
  }

  @Override
  public void close(DefaultMQPushConsumer consumer, java.time.Duration remaining) {
    long budget = Math.max(1, (remaining.toNanos() + 999999) / 1000000);
    long configured = consumer.getAwaitTerminationMillisWhenShutdown();
    consumer.setAwaitTerminationMillisWhenShutdown(
        configured <= 0 ? budget : Math.min(configured, budget));
    consumer.shutdown();
  }

  @Override
  public UpdatePlan updatePlan(RocketMqSettings previous, RocketMqSettings next) {
    return io.github.samhom.bridle.settings.SettingsUpdates.plan(previous, next);
  }

  @Override
  public CredentialsProvider credentials(RocketMqSettings settings) {
    return settings.credentials();
  }
}
