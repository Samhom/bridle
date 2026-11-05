package io.github.samhom.bridle.rocketmq;

import io.github.samhom.bridle.core.*;
import java.time.Duration;
import java.util.UUID;
import org.apache.rocketmq.client.producer.DefaultMQProducer;

public final class RocketMqProducerAdapter
    implements DriverAdapter<DefaultMQProducer, RocketMqSettings> {
  private final org.apache.rocketmq.client.producer.TransactionListener transactions;

  public RocketMqProducerAdapter() {
    this.transactions = null;
  }

  /** Listener must persist decisions by business key so broker checks survive process restarts. */
  public RocketMqProducerAdapter(
      org.apache.rocketmq.client.producer.TransactionListener transactions) {
    this.transactions = java.util.Objects.requireNonNull(transactions);
  }

  @Override
  public String id() {
    return "rocketmq-remoting5";
  }

  @Override
  public void validate(RocketMqSettings settings) {
    RemotingAuthentication.validateTransport(settings);
  }

  @Override
  public ClientException.Kind classify(Throwable error, Operation operation) {
    return RemotingFailures.classify(error, operation);
  }

  @Override
  public void validateUpdate(RocketMqSettings previous, RocketMqSettings next) {
    RemotingAuthentication.validateUpdate(previous, next);
  }

  @Override
  public DefaultMQProducer create(RocketMqSettings settings) {
    validate(settings);
    DefaultMQProducer producer;
    if (transactions == null)
      producer =
          new DefaultMQProducer(
              settings.group(), RemotingAuthentication.hook(settings.credentials()));
    else {
      var transactional =
          new org.apache.rocketmq.client.producer.TransactionMQProducer(
              settings.group(), RemotingAuthentication.hook(settings.credentials()));
      transactional.setTransactionListener(transactions);
      transactional.setCheckThreadPoolMinSize(1);
      transactional.setCheckThreadPoolMaxSize(2);
      transactional.setCheckRequestHoldMax(256);
      producer = transactional;
    }
    producer.setNamesrvAddr(settings.nameServer().authority());
    producer.setUseTLS(settings.nameServer().tls());
    producer.setInstanceName("bridle-" + UUID.randomUUID());
    producer.setSendMsgTimeout(Math.toIntExact(settings.requestTimeout().toMillis()));
    producer.setMqClientApiTimeout(Math.toIntExact(settings.requestTimeout().toMillis()));
    producer.setRetryTimesWhenSendFailed(0);
    producer.setRetryTimesWhenSendAsyncFailed(0);
    producer.setRetryAnotherBrokerWhenNotStoreOK(false);
    producer.setMaxMessageSize(settings.maxMessageBytes());
    producer.setClientCallbackExecutorThreads(2);
    producer.setEnableBackpressureForAsyncMode(true);
    producer.setBackPressureForAsyncSendNum(1024);
    producer.setBackPressureForAsyncSendSize(16 * 1024 * 1024);
    return producer;
  }

  @Override
  public void warmup(DefaultMQProducer client, Duration timeout) throws Exception {
    client.start();
  }

  @Override
  public void close(DefaultMQProducer client) {
    client.shutdown();
  }

  @Override
  public AdapterCapabilities capabilities() {
    return AdapterCapabilities.nativeApi(true);
  }

  @Override
  public UpdatePlan updatePlan(RocketMqSettings previous, RocketMqSettings next) {
    return io.github.samhom.bridle.settings.SettingsUpdates.plan(previous, next);
  }

  @Override
  public java.time.Duration operationTimeout(RocketMqSettings settings, Operation operation) {
    return settings.requestTimeout();
  }

  @Override
  public CredentialsProvider credentials(RocketMqSettings settings) {
    return settings.credentials();
  }
}
