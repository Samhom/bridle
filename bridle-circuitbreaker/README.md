# bridle-circuitbreaker

计数窗口熔断。公共类型在 `io.github.samhom.bridle.circuitbreaker`，状态事件在 `event`，滑动窗口在 `internal`。

运行时通过 `CircuitBreakerRegistry` 按资源名保存实例。这个模块不依赖 `bridle-core`。
