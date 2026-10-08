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
| 25 | `wait-for-callback-failure` | Reuse case 17’s public handler; the driver fails its callback without Error only after invocation completion. The shared validator checks the observed empty error payload and leaf status. |
| 26 | `external-callback-completion-replay` | Observe a root callback completion, checkpoint its result, then revisit it through two later callback barriers without duplicate terminal export. |

Cases 22 and 23 require a valid active `SpanContext` and create/end ordinary
`conformance.<label>` user spans with `conformance.callback=<label>`. They do not
set parents, attach contexts, copy the execution ARN onto user spans, or create
SDK spans. The shared validator determines the canonical trace from SDK spans
and checks the actual user-span parents.

Root handler probes preserve a valid same-trace ambient Lambda parent. Otherwise
the plugin activates its canonical Invocation/Workflow context on the handler
thread and restores it in the same-thread end hook. The plugin requires the
core's 2.2.2 lifecycle capability; older cores are rejected during configuration.
Nested callbacks retain their named context or attempt parents. Execution-view
context placeholders can be valid without recording, so probes do not require
`isRecording()`.

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
validate the newly added cases. The configured OTel conformance workflow owns
deployment and backend validation.

Case 26 creates `otel-external-target` directly on the root context and reads its
stored result on every replay. The `otel-external-target-observed` step returns
that result unchanged and runs only on the first completion. Two later
`waitForCallback` barriers force two more handler replays. The shared driver sends
each callback success only after its creating invocation has completed, producing
four invocations and the final result `target/one/two`. Lossless S3 telemetry must
show one terminal export for each callback; the first target completion precedes
the observation-step attempt. This is SDK cloud behavior coverage, not a claim
that a deployed Lambda exercises the JS local-runner wrapper.

Cases 25–26 use matched shared requirements and workflow pin
`ad35bd36b67821d52a17db4f9d80d8696694a7f3`. The shared cloud checks establish the
service error-payload representation and callback phase gating; local runner
checks do not establish those service facts.

For case 25, Java reports no plugin error details for an entirely empty error
container. The persisted container, callback failure, and caller error stay
unchanged. Any present type, message, data, or stack-trace field remains an error
detail, including an empty string or an explicitly supplied empty stack list.
