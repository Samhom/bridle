# Spring Boot resources

The starter artifact is `bridle-spring-boot3`. Shared auto-configuration lives in `io.github.samhom.bridle.springboot3`. Redis, Elasticsearch, Couchbase and RocketMQ auto-configuration live in the `redis`, `elasticsearch`, `couchbase` and `rocketmq` subpackages. Health lives in `health`.

The starter creates a ClientRuntime only when the application has not supplied one. Native driver
factories load conditionally when that adapter and its native client are present. Native adapters and
Actuator are optional dependencies: add only the adapters used by your application. Application resource
factories can replace the default beans named bridleRedisFactory, bridleSearchFactory,
bridleDocumentsFactory or bridleMessagingFactory. Custom ResourceFactory<C,S> implementations
retain their actual settings and native types; they do not place vendor dependencies in the core.

Example (values refer to trusted infrastructure, not user input):

```yaml
bridle:
  application: orders
  environment: production
  resources:
    cache:
      driver: REDIS
      host: redis.example.internal
      port: 6379
      tls: true
      credentials-bean: redisCredentials
      database: 0
      max-in-flight: 128
      connect-timeout: 2s
      operation-timeout: 3s
      max-payload-bytes: 1048576
```

Supply redisCredentials as a CredentialsProvider bean backed by your existing credential mechanism.
There are no password/token properties. ELASTICSEARCH uses max-batch-items and max-connections;
COUCHBASE uses bucket/scope/collection; ROCKETMQ uses group/topic/consumer-threads and registers a
producer. Register ExclusiveConsumer beans explicitly for handlers with application-owned semantics.
A configured resource without exactly one matching factory fails startup rather than being ignored.
Resource registrations close with the context, including when the runtime was supplied by the user;
other registrations in that runtime are not owned by the resource binding.

BridleResources.clients() exposes a read-only map of managed handles. Configuration metadata covers
runtime and resource properties for IDE completion. Unknown fields are rejected by Boot binding.
With Actuator present, bridleHealthIndicator reports only lifecycle readiness and aggregate counts;
it makes no network calls and does not expose settings, hosts or credentials. Existing application
endpoint exposure/authentication settings remain in control. Set bridle.health-enabled=false to
disable it. Local health UP is not evidence that the remote database is currently reachable.
