package io.github.samhom.bridle.springboot3;

import io.github.samhom.bridle.core.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(BridleProperties.class)
public class BridleAutoConfiguration {
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(BridleResources.class)
  public BridleResources bridleResources(
      ClientRuntime runtime,
      BridleProperties properties,
      ObjectProvider<ResourceFactory<?, ?>> factories,
      org.springframework.beans.factory.BeanFactory beans) {
    return new BridleResources(runtime, properties, factories.orderedStream().toList(), beans);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(ClientRuntime.class)
  public ClientRuntime bridleRuntime(
      BridleProperties properties,
      ObjectProvider<Observer> observers,
      ObjectProvider<AdmissionPolicy> admission) {
    Observer observer = new CompositeObserver(observers.orderedStream().toArray(Observer[]::new));
    AdmissionPolicy policy =
        (resource, operation) ->
            admission.orderedStream().forEach(item -> item.check(resource, operation));
    return new ClientRuntime(
        properties.getMaxClients(),
        properties.getCallThreads(),
        properties.getQueuedCalls(),
        observer,
        policy);
  }
}
