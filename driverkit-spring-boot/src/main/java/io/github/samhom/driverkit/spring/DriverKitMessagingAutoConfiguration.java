package io.github.samhom.driverkit.spring;

import io.github.samhom.driverkit.core.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = DriverKitAutoConfiguration.class)
@ConditionalOnClass(
    name = {
      "org.apache.rocketmq.client.producer.DefaultMQProducer",
      "io.github.samhom.driverkit.rocketmq.ManagedProducer"
    })
public class DriverKitMessagingAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(name = "driverKitMessagingFactory")
  public ResourceFactory<
          org.apache.rocketmq.client.producer.DefaultMQProducer,
          io.github.samhom.driverkit.rocketmq.RocketMqSettings>
      driverKitMessagingFactory() {
    return new ResourceFactory<>() {
      public ResourceProperties.Driver driver() {
        return ResourceProperties.Driver.ROCKETMQ;
      }

      public io.github.samhom.driverkit.rocketmq.RocketMqSettings settings(
          ResourceProperties p, CredentialsProvider c) {
        return new io.github.samhom.driverkit.rocketmq.RocketMqSettings(
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
              io.github.samhom.driverkit.rocketmq.RocketMqSettings>
          adapter() {
        return new io.github.samhom.driverkit.rocketmq.RocketMqProducerAdapter();
      }
    };
  }
}
