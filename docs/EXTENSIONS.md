# Runtime diagnostics and native extensions

## 21: runtime pressure

`ManagedClient.metrics()` exposes in-flight leases, queued calls, active workers, bounded queue capacity,
cleanup failures, rejected configuration updates and retiring-generation age. Shared execution lanes report
shared queue/worker pressure; these are not native connection-pool utilization numbers.
`ResourceMeters(registry,client)` binds pressure gauges with an explicit close/unregister lifetime.
`MicrometerObserver` records `bridle.client.queue.wait` when a call reaches a worker.
`ExclusiveConsumer.inFlight()` and `state()` expose delivery drain pressure. Native pool-specific meters
still require native instrumentation; no utilization is estimated from call counts.

## 22: metadata diagnostics

Install `DiagnosticBuffer` as an Observer (or in CompositeObserver). Capacity is 1–10,000 and sampling is
one in N starts; slow calls and sampled failures retain timestamps, resource identity, revision, outcome
and elapsed time only. Snapshots are immutable. Poll publication history and pass failures to
`configuration(resource,status)` when configuration diagnostics are needed. No payload, key, credential,
exception or user-supplied message is retained. The buffer is diagnostic, not a persistent audit log.

## 23: Redis

`ManagedRedis.getAll`, `eval` and `setBatch` cap command counts and write sizes. `setBatch(...,true)` uses
one borrowed connection for MULTI/EXEC; false uses a single bounded pipeline. A null EXEC is a
discarded transaction (nothing applied). A pipeline element that is an exception means an unknown
prefix may already have been applied, so the call is WRITE_OUTCOME_UNKNOWN and is not retried.
Native objects do not escape. Scripts must be trusted deployment assets, not end-user input. No write is retried by Bridle.
`RedisTopologyAdapter` supports native RedisSentinelClient and RedisClusterClient under ManagedClient
ownership. Seeds and discovered infrastructure must be trusted. TLS hostname verification remains on.
ManagedTopologyRedis now allows bounded explicit MOVED/ASK routing while blocking connection-error
replay after dispatch, and applies per-call pool/socket budgets. See REDIS_TOPOLOGY.md for the pinned
Jedis evidence, precise managed facade scope, independent Sentinel credential gate and test boundaries.
Native escape APIs retain configuration ceilings rather than per-call physical deadline guarantees.
Sentinel failover in a real broker deployment still requires acceptance validation.

## 24: Elasticsearch

`bulkReportAsync` returns the native response including every failed item's status without replaying
successful writes. Existing batch/document/aggregate request budgets apply. Automatic bulk retry is
intentionally absent: callers must select failed items and apply operation-specific idempotency.
`openPointInTime` pins a transport generation; use try-with-resources. Pages require size 1–1000 and
explicit sort, accept search_after, and update the returned PIT ID. PIT overrides, index overrides,
scroll and nonzero from are rejected. Keep-alive is at most one minute. Close releases the server PIT
and the generation lease; server expiry bounds abandoned contexts when revocation prevents cleanup.
Use PIT/search_after for new pagination; no separate Scroll session is introduced.

## 25: Couchbase

`JsonCodec<T>` explicitly maps bounded JSON rather than accepting polymorphic class names.
Typed reads preserve CAS. Typed or JSON upserts accept native DurabilityLevel. `lookupInAsync` caps
paths at 16; `mutateInAsync` caps JSON values and supports CAS plus durability, with no automatic replay.
Native results retain their per-path status; native exceptions remain the cause of ClientException.
`transaction(work,durability)` pins the managed generation and passes the remaining call budget to the
native transaction coordinator. The native driver may replay the callback internally. The callback must
contain transactional database work only; do not send messages, call services, or mutate local external
state inside it. Cancellation is not rollback proof: resolve ambiguous commit outcomes using application
idempotency. Transactions require a non-NONE durability level. New transaction/subdocument behavior
still needs deployment-specific real-cluster acceptance, including replica topology and durability.

## Validation scope

This iteration uses targeted unit/protocol tests and compilation; it does not add a full local regression
run. No new dependency, anonymous endpoint, TLS bypass or credential logging was introduced.
