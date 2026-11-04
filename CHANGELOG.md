# Changelog

## [Unreleased]

- Managed lifecycle and call budgets, bounded execution lanes, revisioned configuration, credential
  rotation/revocation, error classification and resource-scoped observability.
- Modern Redis, Elasticsearch, Couchbase and RocketMQ integrations, with isolated legacy migration
  adapters and an explicitly experimental RocketMQ gRPC profile. Compatibility scope is maintained in
  docs/COMPATIBILITY.md; experimental/legacy code is not automatically approved for production.
- Redis bounded batch/Lua/pipeline/transaction helpers and opt-in Sentinel/Cluster adapter;
  Elasticsearch inspectable bulk results and leased PIT pagination; Couchbase typed JSON codecs,
  CAS, durability, subdocument and native transaction entry points.
- RocketMQ ordered/delayed/batch/transaction sends and guarded concurrent/orderly consumer policies.
- Spring Boot typed resource properties, conditional native factories, configuration metadata and
  lifecycle health reporting without adding a remote probe to every health request.
- Optional performance harness, replayable lifecycle stress tests, reviewed API baselines and a manual
  source/Javadoc/SBOM/signature release-bundle workflow.

### Migration notes

Use docs/MIGRATION.md before upgrading or binding a legacy driver. The 0.1.0-SNAPSHOT development API
has not been declared a stable published API. Mutable settings require an explicit defensive schema.
Use separate resource bindings for database/bucket/topic/group identity changes. A timed-out write may
have an unknown outcome; idempotency belongs to the application. No helper silently retries mutations.

### Validation notes

Targeted local tests and compilation establish only their stated fixture scope. Real broker offset,
transaction, delay, rebalance and DLQ behavior still requires the applicable acceptance environment.
Unsigned preview builds do not verify release signing or authorize a package publication.

Replace Unreleased with a versioned entry including immutable CI/acceptance evidence before tagging.
