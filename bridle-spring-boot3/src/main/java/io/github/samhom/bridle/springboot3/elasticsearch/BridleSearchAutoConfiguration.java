package io.github.samhom.bridle.springboot3.elasticsearch;

import io.github.samhom.bridle.core.*;
import io.github.samhom.bridle.springboot3.BridleAutoConfiguration;
import io.github.samhom.bridle.springboot3.ResourceFactory;
import io.github.samhom.bridle.springboot3.ResourceProperties;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = BridleAutoConfiguration.class)
@ConditionalOnClass(
    name = {
      "co.elastic.clients.elasticsearch.ElasticsearchClient",
      "io.github.samhom.bridle.elasticsearch.ManagedSearch"
    })
public class BridleSearchAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(name = "bridleSearchFactory")
  public ResourceFactory<
          co.elastic.clients.elasticsearch.ElasticsearchClient,
          io.github.samhom.bridle.elasticsearch.ElasticsearchSettings>
      bridleSearchFactory() {
    return new ResourceFactory<>() {
      public ResourceProperties.Driver driver() {
        return ResourceProperties.Driver.ELASTICSEARCH;
      }

      public io.github.samhom.bridle.elasticsearch.ElasticsearchSettings settings(
          ResourceProperties p, CredentialsProvider c) {
        return new io.github.samhom.bridle.elasticsearch.ElasticsearchSettings(
            p.endpoint(),
            c,
            p.getConnectTimeout(),
            p.getOperationTimeout(),
            p.getMaxConnections(),
            p.getMaxBatchItems(),
            p.getMaxPayloadBytes());
      }

      public DriverAdapter<
              co.elastic.clients.elasticsearch.ElasticsearchClient,
              io.github.samhom.bridle.elasticsearch.ElasticsearchSettings>
          adapter() {
        return new io.github.samhom.bridle.elasticsearch.ElasticsearchAdapter();
      }
    };
  }
}
