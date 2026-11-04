package io.github.samhom.driverkit.spring;

import io.github.samhom.driverkit.core.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = DriverKitAutoConfiguration.class)
@ConditionalOnClass(
    name = {
      "co.elastic.clients.elasticsearch.ElasticsearchClient",
      "io.github.samhom.driverkit.elasticsearch.ManagedSearch"
    })
public class DriverKitSearchAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(name = "driverKitSearchFactory")
  public ResourceFactory<
          co.elastic.clients.elasticsearch.ElasticsearchClient,
          io.github.samhom.driverkit.elasticsearch.ElasticsearchSettings>
      driverKitSearchFactory() {
    return new ResourceFactory<>() {
      public ResourceProperties.Driver driver() {
        return ResourceProperties.Driver.ELASTICSEARCH;
      }

      public io.github.samhom.driverkit.elasticsearch.ElasticsearchSettings settings(
          ResourceProperties p, CredentialsProvider c) {
        return new io.github.samhom.driverkit.elasticsearch.ElasticsearchSettings(
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
              io.github.samhom.driverkit.elasticsearch.ElasticsearchSettings>
          adapter() {
        return new io.github.samhom.driverkit.elasticsearch.ElasticsearchAdapter();
      }
    };
  }
}
