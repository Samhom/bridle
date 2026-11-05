package io.github.samhom.bridle.spring;

import io.github.samhom.bridle.core.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = BridleAutoConfiguration.class)
@ConditionalOnClass(
    name = {
      "org.apache.rocketmq.client.producer.DefaultMQProducer",
      "io.github.samhom.bridle.rocketmq.ManagedProducer"
    })
public class BridleMessagingAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(name = "bridleMessagingFactory")
  public ResourceFactory<
          org.apache.rocketmq.client.producer.DefaultMQProducer,
          io.github.samhom.bridle.rocketmq.RocketMqSettings>
      bridleMessagingFactory() {
    return new ResourceFactory<>() {
      public ResourceProperties.Driver driver() {
        return ResourceProperties.Driver.ROCKETMQ;
      }

      public io.github.samhom.bridle.rocketmq.RocketMqSettings settings(
          ResourceProperties p, CredentialsProvider c) {
        return new io.github.samhom.bridle.rocketmq.RocketMqSettings(
            p.endpoint(),
            c,
            p.getGroup(),
            p.getTopic(),
            p.getOperationTimeout(),
            p.getConsumerThreads(),
            p.getMaxPayloadBytes());
      }

      public DriverAdapter<
              org.apache.rocketmq.client.producer.DefaultMQProducer,
              io.github.samhom.bridle.rocketmq.RocketMqSettings>
          adapter() {
        return new io.github.samhom.bridle.rocketmq.RocketMqProducerAdapter();
      }
    };
  }
}
