# Java OpenTelemetry conformance handlers

The shared conformance repository owns the requirements and validators. This
module supplies public-API handlers and SAM resources for both tracing views.

Cases 1–20 are documented in the shared
[invocation-view requirements](https://github.com/aws/aws-durable-execution-conformance-tests/tree/main/packages/aws-durable-execution-conformance-tests-otel/test-requirements/otel-invocation)
and [execution-view requirements](https://github.com/aws/aws-durable-execution-conformance-tests/tree/main/packages/aws-durable-execution-conformance-tests-otel/test-requirements/otel-execution).
For case N, `otel-invocation-N.yaml` and `otel-execution-N.yaml` define the
scenario, input, expected execution history/result, and telemetry assertions
for each view. The [shared OTel README](https://github.com/aws/aws-durable-execution-conformance-tests/blob/main/packages/aws-durable-execution-conformance-tests-otel/README.md)
explains the suites and how to run them.

The Java implementations are the numbered `OtelN...` classes in the
[handler directory](src/main/java/software/amazon/lambda/durable/conformance/otel/).
The [SAM template](template.yaml) maps each case to its handler in both views.
When investigating a run, read the shared requirements at the
`conformance_test_ref` selected by the [OTel workflow](../.github/workflows/otel-conformance-tests.yml).

Case 11 pauses at `otel-invoke-replay-barrier` after the child invocation returns.
The shared driver completes that callback after the second caller invocation
ends. The third invocation replays the saved invoke result and returns it,
without starting another child or exporting another terminal invoke span.
The workflow pins the matching requirements from shared conformance PR #131.
The shared cloud run checks the service trace carrier and full X-Ray ancestry;
local replay tests do not establish those service-side properties.

The following notes cover cases 21–26.

| Case | Scenario | Behavior |
| --- | --- | --- |
| 21 | `completed-step-replay` | Complete a step, suspend on a one-second durable wait, then complete another step. The first step body is skipped on replay. |
| 22 | `user-function-context` | Probe handler entry/restoration/resume, step, child, concurrent parallel branches and map iterations, and their nested steps. |
| 23 | `callback-function-context` | Probe step retry attempts, condition checks, callback submission, a wrapped retry helper's body and strategy, and a virtual child after the asynchronous work finishes. |
| 24 | `invocation-retry-status` | Throw the public retryable execution exception after a checkpointed step, then recover on replay. |
| 25 | `caught-callback-failure-replay` | Catch the expected public callback failure, checkpoint `caught`, then resume through two typed String callback barriers and return `caught/one/two`. The failed leaf stays `ERROR` without an exception event. |
| 26 | `external-callback-completion-replay` | Observe a root callback completion, checkpoint its result, then revisit it through two later callback barriers without duplicate terminal export. |

Cases 22 and 23 require a valid active `SpanContext` and create/end ordinary
`conformance.<label>` user spans with `conformance.callback=<label>`. They do not
set parents, attach contexts, copy the execution ARN onto user spans, or create
SDK spans. The shared validator determines the canonical trace from SDK spans
and checks the actual user-span parents.

Root handler probes preserve a valid same-trace ambient Lambda parent. Otherwise
the per-invocation plugin activates its canonical Invocation/Workflow context on the handler
thread through its explicit handler scope and restores it on that same thread before End.
Use the matching 3.x core and plugin factory API. Nested callbacks retain their named context
or attempt parents. Execution-view context placeholders can be valid without recording, so
probes do not require `isRecording()`.

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

Case 25 requires the matching caught-failure/replay shared requirement revision;
its successful result is incompatible with the older unhandled-failure requirement.
The owning workflow pairs handler routing with the qualified shared source ref.
Shared cloud checks establish the service error-payload representation and callback
phase gates; local runner checks do not establish those service facts.

Case 25 catches `CallbackFailedException` and the documented
`ChildContextFailedException` replay fallback around its first `waitForCallback`.
The fallback retains the actual failed context when its nested callback/submitter
details have been pruned; it does not invent a callback failure category or ID.
The marker step and both barriers remain outside that catch. Each barrier uses
`String.class` callback deserialization, and the marker returns its checkpointed
value rather than mutating a captured variable. Four invocations produce
`PENDING`, `PENDING`, `PENDING`, then `SUCCEEDED`. Replaying the cached failed child
can reach the catch without rerunning its callback body; this does not claim a
direct callback revisit after child-history pruning. Case 17 remains unhandled.

For case 25, Java reports no plugin error details for an entirely empty error
container. The persisted container, callback failure, and caller error stay
unchanged. Any present type, message, data, or stack-trace field remains an error
detail, including an empty string or an explicitly supplied empty stack list.
