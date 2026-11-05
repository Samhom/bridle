# Seeded lifecycle stability checks

`LifecycleModelStressTest` runs a short default schedule (6 seeds, 4 concurrent callers, 80 operations
per caller) as an ordinary core test. The fake native driver is an independent ownership oracle: it
fails if a resource is used after close, closed during physical work, closed twice or never closed.
It injects readiness failures into selected reload candidates so failed generations also have to drain.
The test does not use a live database, sleep to impose an operation order, or assume a specific thread
schedule. Seeds reproduce each caller's operation stream; operating-system interleavings remain variable.

The schedule mixes resource registration/re-registration, asynchronous native work and cancellation,
borrowed sessions, configuration publication and stale updates, revocation, repeated close and delayed
close of older handles. It checks generation/settings coherence, monotonically observed effective
revisions, no resurrection of revoked/closed handles, final registry/resource/isolated-thread counts,
and no additional `bridle-` threads after a round closes. Existing baseline threads are excluded.

Run only this test:

```text
mvn -B -ntp -s .mvn/settings.xml -pl bridle-core test -Dtest=LifecycleModelStressTest
```

For a longer soak, select explicit bounds rather than changing normal CI:

```text
mvn -B -ntp -s .mvn/settings.xml -pl bridle-core test -Dtest=LifecycleModelStressTest -Dbridle.stress.seed=6221094 -Dbridle.stress.rounds=500 -Dbridle.stress.steps=1000
```

Both rounds and per-caller steps accept 1–10,000. Each worker has a completion deadline, native calls
and shutdowns have budgets, and fixtures use a fixed number of executors. An assertion prints the seed
and command properties needed to replay that round. Preserve the full failure, Java version and commit;
repeat the same seed to explore different interleavings. A short successful run validates these
invariants under its observed schedules; it does not establish production-duration stability, prove
linearizability for all schedules or replace product-specific cluster/fault acceptance tests.
