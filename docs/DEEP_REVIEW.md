# Deepening acceptance: items 31–40

The objective is a maintainable production SDK with preserved native semantics and evidence-backed
lifecycle/upgrade guarantees. More convenience methods alone do not meet that objective.

Expert additions beyond feature requests: inspect physical completion after logical timeout; trace
configuration, credential and connection-generation ownership; test actual packaged artifacts from a
fresh consumer; deliberately create negative cases; state exactly which layers each result proves.

| Item | Primary material | Method/tool | Independent acceptance evidence |
| --- | --- | --- | --- |
| 31 PIT ownership | ManagedClient/ClientSession lease paths, pinned Elasticsearch native API and transport options | native Java API Client with controllable late transport completions; core generation tests | old generation/config/revision coherence, no premature close, late PIT IDs disposed, no duplicated sort arrays |
| 32 Redis topology | pinned Jedis 8.0.2 ClusterCommandExecutor/provider/connection bytecode | javap, bounded RESP fixtures, command-dispatch counting | MOVED/ASK routing versus ambiguous connection failure, pool/socket deadline and no blind mutation replay |
| 33 Couchbase JSON | java-client 3.13.0 GetResult and transcoder bytecode, native flags and pinned shaded parser | real GetResult fixtures and native streaming JSON parser | limits before tree/String/application decoding, raw CAS/value preservation, trailing JSON/depth/UTF-8 rejection |
| 34 Boot lifecycle | actual conditional configuration, resource factories, credential subscription | real runtime + RotatingCredentialsProvider + context/property tests | preflight failure starts no native clients, rotation/revocation active, unrelated registrations survive |
| 35 artifact acceptance | installed POM/JAR hashes, native embedded pom.properties, exact Maven graph, GitHub workflow diagnostics | fresh consumer compilation/runtime CodeSource, workflow context contract checks | consumer loads packaged JARs rather than reactor classes, optional-classpath startup, no duplicate original native providers |
| 36 deadline millis | `CallContext` nano deadline, Jedis/Elasticsearch/Couchbase timeout units | floor conversion shared by `CallContext.millisWithin`; sub-millisecond counterexample | 1.999999ms stays 1ms; 999999ns is TIMEOUT instead of a 1ms socket budget |
| 37 search pagination | Java API Client `SearchRequest` scroll/from fields and the existing PIT page contract | request builder fixtures, no cluster | scroll and non-zero `from` rejected before a native call; bounded search still accepted |
| 38 Redis batch outcomes | Redis MULTI/EXEC nil versus pipeline per-command errors | pure outcome classifier plus the existing RESP facade | discarded EXEC is CONFLICT (nothing applied); a throwable pipeline element is WRITE_OUTCOME_UNKNOWN |
| 39 consume ack boundary | RocketMQ one-status-per-callback and nullable rebalance sets | real `DefaultMQPushConsumer` listener invocation, no broker | null assignment becomes an empty snapshot; size other than 1 never reaches the handler |
| 40 circuit vs ambiguous write | circuit epoch/probe implementation and write-outcome classification | deterministic clock, one-failure threshold | WRITE_OUTCOME_UNKNOWN leaves the circuit CLOSED so later reads are not shed |

Prior immutable evidence: Build and verify run 41787111992 and Real cluster recovery run 41787111905
passed at e61a632. Release workflow run 41787110587 failed before jobs were created. A YAML parser was
insufficient: jobs.<job_id>.env does not permit runner context. The new runtime setup uses RUNNER_TEMP
inside a step; a scope counterexample test covers the previous invalid expression.

Sources are the pinned binaries actually used by this repository, not inferred behavior from latest
versions. Public primary references:
- https://docs.couchbase.com/java-sdk/current/howtos/transcoders-nonjson.html
- https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#context-availability

See PIT_SESSIONS.md, REDIS_TOPOLOGY.md, COUCHBASE_JSON.md, BOOT_LIFECYCLE.md and ARTIFACT_ACCEPTANCE.md
for per-item contracts, reproduction commands and limitations. Local protocol/native-fixture tests do
not establish live multi-node cluster recovery or production-duration stability. Artifact linkage tests
do not contact databases. Real signing needs the operator's protected release environment and keys.
