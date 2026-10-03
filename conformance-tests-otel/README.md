# Java OpenTelemetry conformance handlers

The shared conformance repository owns the requirements and validators. This
module supplies public-API handlers and SAM resources for both tracing views.
Existing cases 1–20 and their resources are unchanged by the additions below.

| Case | Scenario | Behavior |
| --- | --- | --- |
| 21 | `completed-step-replay` | Complete a step, suspend on a one-second durable wait, then complete another step. The first step body is skipped on replay. |
| 22 | `user-function-context` | Probe handler entry/restoration/resume, step, child, concurrent parallel branches and map iterations, and their nested steps. |
| 23 | `callback-function-context` | Probe step retry attempts, condition checks, callback submission, a wrapped retry helper's body and strategy, and a virtual child after the asynchronous work finishes. |
| 24 | `invocation-retry-status` | Throw the public retryable execution exception after a checkpointed step, then recover on replay. |

Cases 22 and 23 require a valid active `SpanContext` and create/end ordinary
`conformance.<label>` user spans with `conformance.callback=<label>`. They do not
set parents, attach contexts, copy the execution ARN onto user spans, or create
SDK spans. The shared validator determines the canonical trace from SDK spans
and checks the actual user-span parents.

Root handler probes may inherit the view's SDK root or a valid same-trace,
non-SDK ambient Lambda parent. Java currently preserves an ambient context
propagated by its configured Java agent rather than binding a new SDK root on
the handler thread. Tests must not replace that existing behavior merely to
make parent names identical across SDKs. Without ambient propagation, the
validity check exposes missing handler context. Nested callbacks must retain
their named context or attempt parents. Execution-view context placeholders
can be valid without recording, so probes do not require `isRecording()`.

Case 23 uses the public step attempt number and checkpointed condition state;
it has no mutable warm-container markers. Its helper and virtual child run
after callback completion, so normal suspension/replay does not repeat those
probes. Only the intentional helper failure is caught. The wrapped helper's
strategy executes inside its established context lifecycle; this does not
establish a parent contract for every other policy callback.

Serialization, map item naming, completion policies, standalone step retry or
condition wait strategies, and callbacks invoked before tracing setup are not
claimed to share the stable user-function hook boundary. Plugin hooks,
extractors, and samplers cannot be required to have made their future SDK span
current. Arbitrary user-created threads are also outside these cases.

Case 24 captures `isReplaying()` at handler entry before consuming the completed
step. Java's public `UnrecoverableDurableExecutionException(ErrorObject, true)`
requests invocation retry; the handler does not inject plugin hooks or
checkpoint messages. Its expected invocation statuses are `RETRYING` then
`SUCCEEDED`. Recovery may repeat telemetry delivery, so this case imposes no
exact operation-export count.

The module builds against the SDK selected by `JAVA_SDK_VERSION` or the
`durable.sdk.version` Maven property. Run matched shared requirements when
validating new cases; a workflow pinned to an older requirement set does not
validate cases 21–24. The existing configured OTel conformance workflow owns
deployment and backend validation.
