package io.github.samhom.driverkit.spring;

import io.github.samhom.driverkit.core.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = DriverKitAutoConfiguration.class)
@ConditionalOnClass(
    name = {
      "com.couchbase.client.java.Cluster",
      "io.github.samhom.driverkit.couchbase.ManagedDocuments"
    })
public class DriverKitDocumentsAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(name = "driverKitDocumentsFactory")
  public ResourceFactory<
          com.couchbase.client.java.Cluster, io.github.samhom.driverkit.couchbase.CouchbaseSettings>
      driverKitDocumentsFactory() {
    return new ResourceFactory<>() {
      public ResourceProperties.Driver driver() {
        return ResourceProperties.Driver.COUCHBASE;
      }

      public io.github.samhom.driverkit.couchbase.CouchbaseSettings settings(
          ResourceProperties p, CredentialsProvider c) {
        return new io.github.samhom.driverkit.couchbase.CouchbaseSettings(
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
              io.github.samhom.driverkit.couchbase.CouchbaseSettings>
          adapter() {
        return new io.github.samhom.driverkit.couchbase.CouchbaseAdapter();
      }
    };
  }
}
