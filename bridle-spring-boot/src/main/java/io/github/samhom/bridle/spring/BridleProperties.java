package io.github.samhom.bridle.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bridle", ignoreUnknownFields = false)
public final class BridleProperties {
  private String application = "application", environment = "default";
  private boolean healthEnabled = true;
  private java.util.Map<String, ResourceProperties> resources = new java.util.LinkedHashMap<>();

  public String getApplication() {
    return application;
  }

  public void setApplication(String v) {
    application = v;
  }

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String v) {
    environment = v;
  }

  public boolean isHealthEnabled() {
    return healthEnabled;
  }

  public void setHealthEnabled(boolean v) {
    healthEnabled = v;
  }

  public java.util.Map<String, ResourceProperties> getResources() {
    return resources;
  }

  public void setResources(java.util.Map<String, ResourceProperties> v) {
    resources = v;
  }

  private int maxClients = 128;
  private int callThreads = 16;
  private int queuedCalls = 256;

  public int getMaxClients() {
    return maxClients;
  }

  public void setMaxClients(int value) {
    maxClients = value;
  }

  public int getCallThreads() {
    return callThreads;
  }

  public void setCallThreads(int value) {
    callThreads = value;
  }

  public int getQueuedCalls() {
    return queuedCalls;
  }

  public void setQueuedCalls(int value) {
    queuedCalls = value;
  }
}
