package io.github.samhom.bridle.spring;

/** Only trusted infrastructure coordinates and credential bean references belong in properties. */
public final class ResourceProperties {
  public enum Driver {
    REDIS,
    ELASTICSEARCH,
    COUCHBASE,
    ROCKETMQ
  }

  private Driver driver = null;

  public Driver getDriver() {
    return driver;
  }

  public void setDriver(Driver v) {
    driver = v;
  }

  private String host = null;

  public String getHost() {
    return host;
  }

  public void setHost(String v) {
    host = v;
  }

  private int port = 0;

  public int getPort() {
    return port;
  }

  public void setPort(int v) {
    port = v;
  }

  private boolean tls = true;

  public boolean isTls() {
    return tls;
  }

  public void setTls(boolean v) {
    tls = v;
  }

  private String credentialsBean = null;

  public String getCredentialsBean() {
    return credentialsBean;
  }

  public void setCredentialsBean(String v) {
    credentialsBean = v;
  }

  private int maxInFlight = 128;

  public int getMaxInFlight() {
    return maxInFlight;
  }

  public void setMaxInFlight(int v) {
    maxInFlight = v;
  }

  private java.time.Duration connectTimeout = java.time.Duration.ofSeconds(2);

  public java.time.Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(java.time.Duration v) {
    connectTimeout = v;
  }

  private java.time.Duration operationTimeout = java.time.Duration.ofSeconds(3);

  public java.time.Duration getOperationTimeout() {
    return operationTimeout;
  }

  public void setOperationTimeout(java.time.Duration v) {
    operationTimeout = v;
  }

  private java.time.Duration shutdownTimeout = java.time.Duration.ofSeconds(5);

  public java.time.Duration getShutdownTimeout() {
    return shutdownTimeout;
  }

  public void setShutdownTimeout(java.time.Duration v) {
    shutdownTimeout = v;
  }

  private int maxConnections = 32;

  public int getMaxConnections() {
    return maxConnections;
  }

  public void setMaxConnections(int v) {
    maxConnections = v;
  }

  private int database = 0;

  public int getDatabase() {
    return database;
  }

  public void setDatabase(int v) {
    database = v;
  }

  private int maxPayloadBytes = 1024 * 1024;

  public int getMaxPayloadBytes() {
    return maxPayloadBytes;
  }

  public void setMaxPayloadBytes(int v) {
    maxPayloadBytes = v;
  }

  private int maxBatchItems = 256;

  public int getMaxBatchItems() {
    return maxBatchItems;
  }

  public void setMaxBatchItems(int v) {
    maxBatchItems = v;
  }

  private String bucket = null;

  public String getBucket() {
    return bucket;
  }

  public void setBucket(String v) {
    bucket = v;
  }

  private String scope = "_default";

  public String getScope() {
    return scope;
  }

  public void setScope(String v) {
    scope = v;
  }

  private String collection = "_default";

  public String getCollection() {
    return collection;
  }

  public void setCollection(String v) {
    collection = v;
  }

  private String group = null;

  public String getGroup() {
    return group;
  }

  public void setGroup(String v) {
    group = v;
  }

  private String topic = null;

  public String getTopic() {
    return topic;
  }

  public void setTopic(String v) {
    topic = v;
  }

  private int consumerThreads = 8;

  public int getConsumerThreads() {
    return consumerThreads;
  }

  public void setConsumerThreads(int v) {
    consumerThreads = v;
  }

  public io.github.samhom.bridle.core.Endpoint endpoint() {
    return new io.github.samhom.bridle.core.Endpoint(host, port, tls);
  }
}
