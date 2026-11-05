package io.github.samhom.bridle.springboot3.redis;

import io.github.samhom.bridle.core.*;
import io.github.samhom.bridle.springboot3.BridleAutoConfiguration;
import io.github.samhom.bridle.springboot3.ResourceFactory;
import io.github.samhom.bridle.springboot3.ResourceProperties;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = BridleAutoConfiguration.class)
@ConditionalOnClass(
    name = {"redis.clients.jedis.RedisClient", "io.github.samhom.bridle.redis.ManagedRedis"})
public class BridleRedisAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(name = "bridleRedisFactory")
  public ResourceFactory<
          redis.clients.jedis.RedisClient, io.github.samhom.bridle.redis.RedisSettings>
      bridleRedisFactory() {
    return new ResourceFactory<>() {
      public ResourceProperties.Driver driver() {
        return ResourceProperties.Driver.REDIS;
      }

      public io.github.samhom.bridle.redis.RedisSettings settings(
          ResourceProperties p, CredentialsProvider c) {
        return new io.github.samhom.bridle.redis.RedisSettings(
            p.endpoint(),
            c,
            p.getConnectTimeout(),
            p.getOperationTimeout(),
            p.getMaxConnections(),
            p.getDatabase(),
            p.getMaxPayloadBytes());
      }

      public DriverAdapter<
              redis.clients.jedis.RedisClient, io.github.samhom.bridle.redis.RedisSettings>
          adapter() {
        return new io.github.samhom.bridle.redis.JedisAdapter();
      }
    };
  }
}
