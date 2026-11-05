# Reproducible performance baseline

Run `tools/run_benchmarks.ps1 -Operations 10000 -Warmup 2000 -Concurrency 4` with JDK 17+.
The opt-in benchmarks profile is excluded from normal builds and published runtime dependencies.
It emits JSON lines for direct/native calls, core, core+Micrometer, and core+Micrometer+recording
OpenTelemetry SDK spans (no network exporter). Each row includes throughput, P99, allocation lower
bound and live threads. Warmup, payload and concurrency are the same across all four modes.

By default the native workload is a synthetic AtomicLong-backed adapter. For actual Jedis GET set
DRIVERKIT_BENCH_REDIS_HOST, PORT, TLS, USER and PASSWORD through the environment. TLS defaults true.
The database workload is read-only on bridle:benchmark:read-only; endpoints must be trusted local
or benchmark infrastructure. Output excludes endpoints, credentials, payloads and native exceptions.
This compares core governance overhead, not every product-specific facade or a production SLA.

Two more rows measure the impact of a blocked resource on a fast resource, with shared and isolated
execution lanes. This uses a 100 ms observation window, not a hardware-specific CI assertion.
Allocation is the positive per-thread byte difference for threads alive in both snapshots; short-lived
thread allocation is omitted, and unsupported JVMs report -1. Never interpret this lower bound as exact
process allocation. Record JVM, CPU/OS, heap, GC, broker/server versions and commit with every baseline.
Use multiple fresh JVM forks and compare distributions on an otherwise idle fixed host. Smoke runs
verify the harness only; do not present their throughput as capacity or a stable regression threshold.
