# Elasticsearch PIT generation ownership

A PIT is a server resource tied to the native transport that opened it. It now retains a managed
session and executes open/pages through ManagedClient session overloads. Each call acquires its own
physical lease from that exact generation; settings, timeout ceilings and observation revision come
from the same frozen snapshot. A foreign handle cannot borrow that session. Ordinary configuration
publication can retire the transport but does not move an existing PIT to the replacement endpoint.
Credential rotation invalidates new work through superseded sessions; resource revocation likewise
closes admission. Neither operation claims to undo already dispatched server work.

The persistent session and each physical call consume the configured in-flight lease budget. Set a
budget of at least two to open/use a PIT. The existing runtime shutdown deadline may still force native
transport cleanup; no session promises to keep a transport alive beyond runtime shutdown.

## Logical completion versus native completion

A logical timeout does not mean an asynchronous native request has completed. PIT ownership tracks
physical open/page stages separately from the caller's future. A failed, interrupted or timed-out page
closes the logical PIT, so another page cannot race a late response that replaces its PIT ID. The late
native response still updates the ID used for cleanup. Cleanup begins after in-flight native work
finishes and uses the original transport and settings. The persistent lease is returned only after
cleanup completes; the managed physical child lease independently remains until its native stage ends.

Internal cleanup is disposal, not a newly admitted business operation. It can attempt to close the PIT
after access revocation using the already owned original transport. A revoked server credential or
broken connection may still reject that cleanup. Keep-alive is limited to one minute and server expiry
remains the fallback if the client cannot learn an ID or cleanup cannot reach the server. Callers must
still use try-with-resources. A close deadline bounds the caller's wait; a pending native cleanup keeps
ownership until its completion or the runtime's forced shutdown.

## Request preservation

PIT pages copy the original JSON body, then set only the managed PIT reference. Native withJson builder
methods already populate sort and search_after arrays; adding those arrays again duplicates them and
changes pagination semantics. Page size, explicit sort, search_after count, index/PIT/scroll overrides
and nonzero from remain checked before dispatch.

## Reproducible evidence

SessionInvocationTest verifies same-generation native/settings/revision coherence across publication,
rejects foreign sessions, verifies physical ownership survives logical timeout, and rejects stale
credential and revoked-resource sessions. PitOwnershipTest uses the actual Elasticsearch Java API
Client over a controllable transport that deliberately ignores deadlines. It proves late-open and
late-page IDs are closed on the original transport, retired transports are not disposed early, logical
PITs do not reopen after failure, and pagination arrays are not duplicated. ElasticsearchTransportTest
also exercises the real HTTP transport against a local protocol fixture. These are deterministic
ownership/protocol checks; they do not claim cluster PIT failover or production server acceptance.
