package io.github.samhom.driverkit.spring;

import io.github.samhom.driverkit.core.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = DriverKitAutoConfiguration.class)
@ConditionalOnClass(
    name = {"redis.clients.jedis.RedisClient", "io.github.samhom.driverkit.redis.ManagedRedis"})
public class DriverKitRedisAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(name = "driverKitRedisFactory")
  public ResourceFactory<
          redis.clients.jedis.RedisClient, io.github.samhom.driverkit.redis.RedisSettings>
      driverKitRedisFactory() {
    return new ResourceFactory<>() {
      public ResourceProperties.Driver driver() {
        return ResourceProperties.Driver.REDIS;
      }

      public io.github.samhom.driverkit.redis.RedisSettings settings(
          ResourceProperties p, CredentialsProvider c) {
        return new io.github.samhom.driverkit.redis.RedisSettings(
            p.endpoint(),
            c,
            p.getConnectTimeout(),
            p.getOperationTimeout(),
            p.getMaxConnections(),
            p.getDatabase(),
            p.getMaxPayloadBytes());
      }

      public DriverAdapter<
              redis.clients.jedis.RedisClient, io.github.samhom.driverkit.redis.RedisSettings>
          adapter() {
        return new io.github.samhom.driverkit.redis.JedisAdapter();
      }
    };
  }
}
