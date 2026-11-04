# Governed Redis topology calls

## Source-level failure contract

The integration is pinned to Jedis 8.0.2. Its `ClusterCommandExecutor.doExecuteCommand` catches
`JedisConnectionException` and `JedisRedirectionException` in the same attempt loop. Increasing
`maxAttempts` alone can therefore replay a mutation whose reply was lost; setting it to one prevents
normal ASK/MOVED handling as well. The public provider methods and protected single-dispatch hook
were checked against the actual 8.0.2 JAR with `javap -p -c`, not inferred from similarly named APIs.

The adapter retains that native routing engine with at most three native attempts. A connection
failure during command dispatch is wrapped in a dedicated `JedisClusterOperationException` subtype,
which the native attempt loop propagates immediately. The original connection cause remains intact,
and the adapter classifies uncertain writes as WRITE_OUTCOME_UNKNOWN. Explicit ASK/MOVED replies
can route again because Redis rejected that command before execution; ASKING remains on the same
borrowed connection as the redirected command. Authentication and arbitrary command errors are not
retried as redirections. Connection acquisition can fail before dispatch without replaying a write.

## One physical budget for supported calls

Use ManagedTopologyRedis for single-key get/set/increment/delete. It carries the ManagedClient
CallContext's absolute monotonic deadline across pool waits, connection/authentication setup, socket
reads and redirect hops. Borrowed socket timeouts are restored before reuse; broken connections are
invalidated through native pool ownership. Queue time has already consumed that same call budget.
Creation and readiness also receive their actual remaining lifecycle budget. Slot refresh directly
uses Jedis's public discoverClusterNodesAndSlots on one bounded connection. Failed refresh ends the
call, rather than entering renewClusterSlots's hidden unbounded node-pool fallback; that fallback can
otherwise wait for a pool connection already held by the same redirected command.

The native client escape is still available for advanced APIs, but does not receive a shorter caller
budget automatically. Native fan-out, pipelines, multi-key operations and blocking workloads are not
claimed as governed facade operations. Blocking CommandObjects are rejected by the dispatch hook;
use a dedicated native binding with an explicit blocking-operation lifecycle. Connection limits remain
per native node, not a global cluster-wide quota. There is no automatic replay after unknown writes.

## Separate Sentinel credentials

Data and Sentinel credential providers may differ. The core credential watch is associated with the
data provider. The topology generation also remembers both providers' initial CredentialSource
versions and checks version/expiry/revocation before dispatch. Changing an independent Sentinel
credential source therefore rejects new calls on the stale generation until an explicit configuration
revision rebuilds it. This is fail-closed invalidation, not an automatic dual-source rotation service.
A provider with no CredentialSource lifetime metadata still needs an explicit configuration revision.
Native discovery/subscription traffic is owned and closed with that generation; it uses configured
transport timeouts and is not a per-user operation. TLS hostname verification remains enabled.

## Independent acceptance evidence

TopologyRoutingTest runs real Jedis against bounded loopback RESP peers. The fixture emits CLUSTER
SLOTS, MOVED, ASK and ASKING responses, records actual increment executions, and deliberately closes
a socket after applying INCR but before returning a reply. The required invariant is one applied
increment and WRITE_OUTCOME_UNKNOWN, never a successful retry. Further cases hold the only pool
connection and stall a response to prove physical work ends under a shorter managed deadline; a
Sentinel fixture proves an independent credential-version change blocks stale-generation commands.
A rejected MOVED refresh must release the physical invocation instead of falling back to its own
exhausted node pool.

These fixtures exercise protocol and lifetime paths, not Redis election or a production failover
algorithm. Real Sentinel election, replica promotion, network partitions and mixed-version Redis
Cluster recovery still need a real-cluster acceptance environment. Trusted catalog/discovery
endpoints must remain under infrastructure control; this adapter is not an arbitrary URL proxy.

Targeted command:

```text
mvn -B -ntp -s .mvn/settings.xml -pl driverkit-redis-jedis -am test -Dtest=TopologyRoutingTest -Dsurefire.failIfNoSpecifiedTests=false
```
