# Release notes

## 0.1.0-SNAPSHOT

开发版本，尚未发布到 Maven Central。这一版调整了模块和包，公开类型有迁移：

- 熔断从 `bridle-core` 拆到 `bridle-circuitbreaker`。`CircuitBreaker`、`CircuitBreakerConfig`、`CircuitBreakerRegistry`、`CircuitStatus` 在 `io.github.samhom.bridle.circuitbreaker`，窗口实现在 `internal`，状态事件在 `event`。
- `ExecutionPolicy` 只保留线程隔离。熔断通过 `ClientPolicy.withCircuitBreaker(CircuitBreakerConfig)` 启用。
- 令牌桶拆到 `bridle-ratelimiter`，包名 `io.github.samhom.bridle.ratelimiter`。`RateLimiterConfig` 取代原来的 `RateLimitPolicy.Limit`。
- 产品配置类移到 `bridle-redis/settings`、`bridle-elasticsearch/settings`、`bridle-couchbase/settings`、`bridle-rocketmq/settings`。类和包名不变，这些构件不带厂商驱动。
- 旧代际目录改为各产品下的 `legacy`。构件名不变。
- Spring Boot 3.5 构件改为 `bridle-spring-boot3`，包名改为 `io.github.samhom.bridle.springboot3` 及其产品子包。
- `api/core-development.json` 按这次公开表面更新。移除的是已经迁走的类型和方法。
