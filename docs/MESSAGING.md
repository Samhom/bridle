# RocketMQ managed messaging

The remoting 5 adapter provides send, sendOrdered, sendDelayed, sendBatch and sendTransaction.
All use the managed admission, revision, deadline, observation and error path. Sends never acquire a
Bridle retry. Non-SEND_OK results remain WRITE_OUTCOME_UNKNOWN. Business keys are application
idempotency keys; they do not make the broker deduplicate messages.

Ordered sends route a key to a stable sorted queue set. Await each send for a key before sending the
next, use ordered consumers, and keep queue topology stable. Cross-queue total order is not promised.
Delay levels 1–18 refer to the broker's schedule configuration. Batches are limited to 256 messages,
4 MiB raw and the current configured encoded message-size ceiling; batches cannot mix topics or delay.

Construct RocketMqProducerAdapter(TransactionListener) for native transaction producers. Persist local
transaction decisions by business key; both execution and later broker-check callbacks must be
idempotent. An unknown local decision must return UNKNOW rather than guessing commit/rollback.
The native transaction API has a configured send timeout rather than a per-call overload: a shorter
managed deadline returns an unknown write outcome while the physical lease remains owned until native
completion. SDK cancellation is not a rollback request. Broker transaction checks can outlive callers.

RocketMqConsumerAdapter.ordered registers a native orderly listener through the same DeliveryGuard as
concurrent handlers. Both listeners accept exactly one message: RocketMQ applies one status to the
whole callback list, so a larger batch is asked to retry and is not shown to application code.
A null rebalance assignment is an empty snapshot, not a crash. An interrupted handler restores the
interrupt flag and still returns the retry status. ConsumerOptions selects bounded retry attempts and first/last offset only when
there is no committed group offset. Reconfiguration does not reset existing offsets. Native balancing,
ACK/retry, offset commit/recovery and retry-to-DLQ transitions stay with RocketMQ. Rebalance observers
must return promptly and cannot change assignments. A bounded shutdown drains admitted handlers before
exclusive restart; if a handler ignores shutdown budgets, the close deadline may force native cleanup.

Use RocketMqSettings.deadLetterTopic(sourceGroup) for a separate DLQ inspection consumer with explicit
broker ACL permission. There is no automatic redrive: inspect failure reason, fix the handler and replay
through the original producer using a preserved business idempotency key. Pause the group before using
broker administration to reset offsets; do not reset offsets during active delivery.

Local tests cover the native send overloads and guarded listeners without a broker. Broker transaction
checks, delay scheduling, rebalance, persistent offset recovery and DLQ transfer require a configured
RocketMQ acceptance environment; no local test claims those broker operations have been exercised.
