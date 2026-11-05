package io.github.samhom.bridle.springboot3.couchbase;

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
      "com.couchbase.client.java.Cluster",
      "io.github.samhom.bridle.couchbase.ManagedDocuments"
    })
public class BridleDocumentsAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(name = "bridleDocumentsFactory")
  public ResourceFactory<
          com.couchbase.client.java.Cluster, io.github.samhom.bridle.couchbase.CouchbaseSettings>
      bridleDocumentsFactory() {
    return new ResourceFactory<>() {
      public ResourceProperties.Driver driver() {
        return ResourceProperties.Driver.COUCHBASE;
      }

      public io.github.samhom.bridle.couchbase.CouchbaseSettings settings(
          ResourceProperties p, CredentialsProvider c) {
        return new io.github.samhom.bridle.couchbase.CouchbaseSettings(
            p.endpoint(),
            c,
            p.getBucket(),
            p.getScope(),
            p.getCollection(),
            p.getConnectTimeout(),
            p.getOperationTimeout(),
            p.getMaxPayloadBytes());
      }

      public DriverAdapter<
              com.couchbase.client.java.Cluster,
              io.github.samhom.bridle.couchbase.CouchbaseSettings>
          adapter() {
        return new io.github.samhom.bridle.couchbase.CouchbaseAdapter();
      }
    };
  }
}
