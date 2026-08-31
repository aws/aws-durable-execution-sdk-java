# PR #661 — Codex Review Findings & Resolution

Workflow Insight plugin (`insight-plugin`).

- **First review pass**: Codex AI on commit `9cb8201` — findings 1–8 below.
- **Second review pass**: Codex AI on the rebased `workflow-insight-plugin`
  branch (post-`2.2.0`, SDK `2.2.1-SNAPSHOT`) — findings 9–12 below, plus a new
  mutable-`Number` observation folded into existing finding 7 and a recorded
  sampling decision on finding 8. These second-review fixes are applied on the
  current rebased branch working tree.

Legend — **Status**: `FIXED` (addressed in this change), `OPEN` (intentionally
left unaddressed for now), `DEFERRED` (blocked on coordinated cross-SDK work).

| # | Finding ID | Pri | Title | Status |
|---|------------|-----|-------|--------|
| 1 | `arf_v1_v6wv3rjugx56mymsuekqwwjamc` | P1 | Publish the new plugin artifact | FIXED |
| 2 | `arf_v1_blkdgaotf7rzua2ga3uyouqm5e` | P1 | Support the SDK's default payload types (Java-time) | FIXED |
| 3 | `arf_v1_xryomwjxrzqt3z55wozggpsrsl` | P2 | Keep remote exports off the checkpoint callback | OPEN |
| 4 | `arf_v1_ig2yofnonmfn7zknekoxwd6xmg` | P2 | Deterministic chronological operation order | FIXED |
| 5 | `arf_v1_lndcqjp4vnrm6vjmmfzrqrfdin` | P2 | Preserve the checkpointed operation error type | FIXED |
| 6 | `arf_v1_oirdg3rnpdnwvpldafyiqrnncz` | P2 | Distinguish included JSON null from an omitted field | OPEN |
| 7 | `arf_v1_rzwdoyquezpr2qgby63imtmxcn` | P2 | Prevent one exporter mutating later exporters' records (incl. mutable `Number`) | FIXED |
| 8 | `arf_v1_47kz274xvx4sow4mmbm7gzor7a` | P3 | Normalize the hash into the half-open interval | DEFERRED |
| 9 | `arf_v1_cyyzil3ide53kqls24cwcz3cts` | P2 | Snapshot execution input before handler/transform mutation | FIXED |
| 10 | `arf_v1_qh6xoafzze3z3ccgrbppucmunr` | P2 | Remove retained suspended execution state | FIXED |
| 11 | `arf_v1_hp5bcyxuzbh2vgqafztcdoghc5` | P2 | Unwrap `UnrecoverableDurableExecutionException` | FIXED |
| 12 | `arf_v1_luk5otghmahig7iqbjsa7g67gu` | P2 | Add `insight-plugin/**` to the build workflow path filter | FIXED |

---

## 1 — Publish the new plugin artifact — `FIXED` (P1)

- Discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3883200348
- Classification: release/packaging gap.
- Problem: the reactor builds `insight-plugin`, but the release pipeline deploys
  and uploads only `sdk`, `sdk-testing`, and `otel-plugin`, so
  `aws-durable-execution-sdk-java-plugin-insight` would be absent from Maven
  Central and the GitHub release.
- Resolution:
  - `.github/scripts/maven_publish.sh`: added a fourth
    `mvn clean deploy ... -pl insight-plugin -P publishing` step, matching the
    existing per-module deploy style.
  - `.github/workflows/publish_maven.yml`: added
    `insight-plugin/target/aws-durable-execution-sdk-java-plugin-insight-${RELEASE_VERSION}.jar`
    to the `gh release upload` list.
  - `RELEASE.md`: updated the artifact list ("… and Workflow Insight plugin")
    and the JAR count ("four JARs") in the publication and verification sections.
- Caveat: the finding also asked for a "release-workflow check". No existing
  automated guard/parser for the release workflow exists in the repo, and a
  bespoke script that greps the workflow for module names would be brittle
  (exactly the anti-pattern to avoid). No guard was added; the `publishing`
  profile is inherited from the parent POM, so the module produces the same
  signed/sources/javadoc artifacts as `otel-plugin`.

## 2 — Support the SDK's default payload types — `FIXED` (P1)

- Discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3883200520
- Classification: correctness / silent data loss.
- Problem: the bare `ObjectMapper` cannot serialize Java-time values (e.g.
  `Instant`), which `JacksonSerDes` supports. An included input/output/result
  containing one made size calculation return `null` and final serialization
  throw; `emit` catches the exception and silently drops every record.
- Resolution:
  - `insight-plugin/pom.xml`: added a direct
    `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` dependency (version
    managed by the imported `jackson-bom`).
  - `Json.MAPPER`: registered `JavaTimeModule` and disabled
    `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` so Java-time values emit as
    ISO-8601 strings.
  - Tests (`JsonJavaTimeTest`): `Instant` stringifies to
    `"2026-08-05T12:34:56Z"`; `byteSize` of a payload containing an `Instant` is
    non-null; end-to-end plugin output with an `Instant` in the execution input
    serializes and carries the ISO-8601 value.

## 3 — Keep remote exports off the checkpoint callback — `OPEN` (P2)

- Discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3883200693
- Second-review discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3884438922
- Classification: performance / durability risk in `ON_CHANGE` mode.
- Problem: `emit` runs synchronously from `onOperationChange`, which the SDK
  invokes before checkpoint futures/pollers complete. Synchronous S3/CloudWatch
  calls plus immediate flush on every change can stall checkpoint coordination
  and risk invocation timeouts.
- Disposition: left OPEN. The suggested fix (queue records on non-terminal
  hooks; perform serial export/flush from `onInvocationEnd`; add a
  delayed-exporter test proving change hooks return promptly) is a non-trivial
  behavioral redesign of the emit lifecycle and is out of scope for this change.
  Not addressed here.
- Second-review note: the second pass re-surfaced this as the "port the JS
  `ExportScheduler` single-slot coalescing scheduler and drain from
  `onInvocationEnd`" observation. Still deferred to a dedicated change; kept
  **OPEN**. Not fixed in this second-review batch (which covers findings 9–12
  plus the mutable-`Number` and sampling items).

## 4 — Deterministic chronological operation order — `FIXED` (P2)

- Discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3883200875
- Classification: correctness / non-determinism.
- Problem: the hook supplies a map with no iteration-order guarantee (the core
  snapshot comes from a concurrent map). Iterating `values()` yields unstable
  operation arrays, and `OperationsIndex` treats the last-encountered repeated
  name as the latest — so an older occurrence could overwrite the real latest
  status/type/subType.
- Resolution:
  - `WorkflowInsight.buildOperationRecords`: sorts `operations.values()` by
    `startTimestamp` ascending (nulls last) with a stable operation-`id`
    tie-breaker (nulls last) *before* filtering/building records. Chronological
    order then flows into `OperationsIndex`, so `operationsByName` latest scalar
    fields (`status`, `type`, `subType`) reflect the chronologically last
    occurrence.
  - Tests (`OperationOrderingTest`): shuffled start timestamps emitted
    chronologically; equal timestamps broken by ascending id; null timestamps
    last; repeated-name latest scalars reflect chronological (not map) order with
    differing status/subType and a preserved `failedCount`.

## 5 — Preserve the checkpointed operation error type — `FIXED` (P2)

- Discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3883201083
- Classification: correctness / observability fidelity.
- Problem: operation and execution snapshot errors arrive wrapped as
  `DurableOperationException` (via `PluginInfoConverter`), so `toErrorInfo`
  emitted the generic wrapper class name, losing the original failure identity.
- Resolution:
  - `WorkflowInsight.toErrorInfo`: when the throwable is a
    `DurableOperationException` with a non-null `ErrorObject`, derives
    `name`/`message` from `errorType()`/`errorMessage()`, falling back to the
    throwable's simple class name / message for any null field. Applies to both
    the operation-level path (`item.error()`) and the execution-level path
    (`executionError`) since both flow through `toErrorInfo`.
  - Tests (`OperationErrorIdentityTest`): asserts exact original type and
    message for both operation-level and execution-level errors, plus the
    fallback path when `errorType` is null.

## 6 — Distinguish included JSON null from an omitted field — `OPEN` (P2)

- Discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3883201219
- Second-review discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3884439231
- Classification: wire-shape fidelity.
- Problem: an enabled-but-`null` value is conflated with content excluded by
  configuration; the wire map omits both. A successful handler returning `null`
  therefore has no `output` field even though output inclusion defaults to true.
- Disposition: left OPEN. The fix (track field presence separately or use an
  explicit JSON-null sentinel, emitting `null` for included terminal values
  while omitting only unavailable/disabled content, plus null input/output
  tests) touches the record's presence/omission model and its cross-SDK wire
  contract; deferred to a dedicated change. Not addressed here.
- Second-review note: re-surfaced as the "presence flags
  (`inputPresent`/`outputPresent`/`resultPresent`), not a sentinel" observation.
  Still a wire-contract change deferred to its own change; kept **OPEN**.

## 7 — Prevent one exporter mutating later exporters' records — `FIXED` (P2)

- Discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3883201369
- Classification: robustness / isolation.
- Problem: the accessor exposed the live operations list (with mutable
  elements), and truncation returns the original record when it already fits, so
  all exporters shared one mutable object — a custom exporter that clears or
  redacts operations/nested content could corrupt every subsequent export.
- Resolution:
  - `WorkflowInsightRecord.operations()` now returns an immutable snapshot
    (`Collections.unmodifiableList`). A package-private `addOperation(...)`
    mutator is used for record construction (allowed here — preview API).
  - Added `WorkflowInsightRecord.deepCopy()` (deep-copies each operation and the
    execution input/output content) and `OperationRecord.deepCopy()`
    (deep-copies the result payload). `Json.deepCopyContent` rebuilds mutable
    `Map`/`List` container structure recursively and converts other serializable
    payload objects into detached JSON-compatible object graphs, preserving the
    emitted JSON while preventing mutable POJO state from being shared between
    exporters (`ErrorInfo` is immutable and shared safely).
  - `WorkflowInsight.emit`: takes a per-exporter `deepCopy()` of the record
    before truncation/shaping, so each exporter operates on an isolated graph.
  - Test (`ExporterIsolationTest`): a hostile first exporter mutates operation
    fields and clears the nested input map; the second exporter still sees the
    pristine operation name/status, nested result content, and input content.
- Second-review discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3884439495
- Isolation scope: mutable `Map`/`List` structures are copied recursively; arbitrary serializable POJOs and all
  `Number` values are converted into detached JSON-compatible objects. Only known-immutable scalar values are shared.
- Second-review observation: the original
  `Json.deepCopyContent` treated *all* `Number` values as an immutable shortcut
  and shared them by reference. Mutable `Number` subclasses —
  `AtomicInteger`/`AtomicLong` or any custom serializable `Number` — would
  therefore be shared across exporters, re-opening the same mutation vector.
  - Fix: removed the broad `Number` shortcut from `Json.deepCopyContent`. Only
    known-immutable scalars (`String`, `Boolean`, `Character`, `Enum`, `null`)
    are shared; every `Number` now falls through `ObjectMapper.convertValue`
    into a detached immutable numeric leaf, so the emitted JSON stays numeric
    while no mutable `Number` reference is shared.
  - Tests (`MutableNumberIsolationTest`): `AtomicInteger`, `AtomicLong`, and a
    custom `@JsonValue` serializable `Number` each detach (not the same
    reference; copy holds the pre-mutation value) and stringify to a plain
    number; an exporter-path test with those types nested top-level, in a list,
    and in a map confirms the captured copy is unaffected by post-emission
    mutation of the originals and the wire JSON stays numeric
    (`"count":3`, `"custom":99`). Status kept **FIXED**.

## 8 — Normalize the hash into the half-open interval — `DEFERRED` (P3)

- Discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3883201548
- Second-review discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3884439574
- Classification: cross-SDK sampling-parity nuance.
- Problem: dividing by max unsigned 32-bit (`0xffffffff`) maps `0xffffffff` to
  exactly `1.0`, yielding `[0,1]` rather than the documented `[0,1)` and
  potentially diverging from other SDKs' deterministic sampling decisions. The
  suggested change divides by `2^32` (`0x1_0000_0000L`).
- Disposition: DEFERRED. Changing the sampling denominator alters the
  deterministic decision boundary and must be coordinated with the JavaScript
  and Java reference implementations so all SDKs agree on the same
  fixed-vector decisions; a Java-only change here would risk cross-SDK drift.
  Per direction, the sampling denominator is **left unchanged** pending that
  coordinated change.
- Second-review decision (recorded): keep Java behavior **identical to JS in
  this PR** — no denominator change (`0xffffffffL` retained in
  `WorkflowInsight.shouldSample`). Any future normalization to the half-open
  interval `[0,1)` (dividing by `2^32`) must be made as a **coordinated change
  across all SDKs** (JS, Python, Java) so deterministic sampling decisions stay
  aligned. Status kept **DEFERRED**.

---

## 9 — Snapshot execution input before handler/transform mutation — `FIXED` (P2)

- Finding ID: `arf_v1_cyyzil3ide53kqls24cwcz3cts` (second review pass).
- Discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3884439030
- Classification: correctness / silent data corruption.
- Problem: `onInvocationStart` cached the *live* `info.executionInput()` object
  reference, and invocation-end records read the live `info.executionInput()`
  again. A user handler that mutates the input object mid-execution, or a
  content transform that mutates its argument in place, would corrupt the value
  emitted at end (and, in `ON_CHANGE`, accumulate corruption across successive
  emissions that reuse the same cached reference).
- Resolution:
  - `WorkflowInsight.onInvocationStart`: detaches the input immediately via
    `Json.deepCopyContent(info.executionInput())` and stores that raw detached
    snapshot in `ExecutionState.cachedInput`, before any handler or transform
    can run.
  - `onInvocationStart` (`ON_CHANGE` start emission) and `onInvocationEnd` now
    build records from `state.cachedInput`, not the live
    `info.executionInput()`.
  - `WorkflowInsight.applyDataContent`: hands each content transform its own
    `Json.deepCopyContent(value)` defensive copy, so a mutating transform
    operates on a throwaway copy and can never corrupt the cached raw snapshot
    reused across emissions.
- Tests (`InputSnapshotTest`): a handler that mutates the live input's nested
  list and adds a key *after* start — the emitted record still reflects the
  input as of start (list size 1, no late key); a mutating input transform
  under `ON_CHANGE` across start/change/end emissions — each emission's output
  carries exactly one transform marker (never accumulating), proving each build
  received a fresh snapshot copy.

## 10 — Remove retained suspended execution state — `FIXED` (P2)

- Finding ID: `arf_v1_qh6xoafzze3z3ccgrbppucmunr` (second review pass).
- Discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3884439128
- Classification: resource leak.
- Problem: `onInvocationEnd` removed per-ARN state only on terminal
  (`SUCCEEDED`/`FAILED`) status. `onInvocationEnd` also fires on non-terminal
  `PENDING`/`RETRYING` suspends, so every suspended execution left a retained
  `ExecutionState` entry in the `byArn` map for the lifetime of the warm
  container — an unbounded leak for long-running suspend-heavy workloads.
- Resolution:
  - `WorkflowInsight.onInvocationEnd`: performs any needed emission first, then
    removes the per-ARN state in a `finally` block on **every** invocation end,
    including `PENDING`/`RETRYING`. Nothing durable is lost — the next
    invocation's `onInvocationStart` recreates the same stable `startTime` from
    `InvocationInfo.executionStartTime()` (stable across resumes), the same
    one-time sampling decision deterministically from the ARN, and the input
    snapshot from `InvocationInfo.executionInput()`.
  - Added a package-private test seam `InsightPlugin.retainedStateCount()`
    (returns `byArn.size()`).
- Tests (`StateCleanupLifecycleTest`): 25 distinct `PENDING` executions leave 0
  retained states; a `RETRYING` suspend also clears state; a full
  suspend→resume→terminal cycle re-seeds the identical stable start time and
  input and ends with 0 retained states. `WorkflowInsightHookTest`'s
  suspend/resume test was updated to model a realistic resume
  (`onInvocationStart` on the resume invocation) and assert
  `retainedStateCount() == 0`.
- Scope note: this deliberately does **not** implement the `ON_CHANGE` export
  scheduler (finding 3), which stays OPEN.

## 11 — Unwrap `UnrecoverableDurableExecutionException` — `FIXED` (P2)

- Finding ID: `arf_v1_hp5bcyxuzbh2vgqafztcdoghc5` (second review pass; extends
  finding 5).
- Discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3884439326
- Classification: correctness / observability fidelity.
- Problem: finding 5 unwrapped the checkpointed `ErrorObject` only from
  `DurableOperationException`. Execution-level failures can instead arrive
  wrapped as `UnrecoverableDurableExecutionException`, which also carries an
  `ErrorObject`; those were emitted with the generic wrapper class name,
  losing the original failure identity.
- Resolution:
  - `WorkflowInsight.toErrorInfo` now routes through a single
    `extractErrorObject(Throwable)` helper that returns the `ErrorObject` from
    either `DurableOperationException` **or**
    `UnrecoverableDurableExecutionException`, preserving the existing null-field
    fallback to the throwable's own class name / message.
- Tests (`UnrecoverableErrorUnwrapTest`): a `FAILED` execution wrapped in
  `UnrecoverableDurableExecutionException` unwraps to its `errorType`/
  `errorMessage`; a `RETRYING` execution under `ON_CHANGE` (non-terminal, still
  emits) likewise unwraps; a null `errorType` falls back to the wrapper class
  name. Existing operation-level `DurableOperationException` behavior remains
  covered by `OperationErrorIdentityTest`.

## 12 — Add `insight-plugin/**` to the build workflow path filter — `FIXED` (P2)

- Finding ID: `arf_v1_luk5otghmahig7iqbjsa7g67gu` (second review pass).
- Discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3884439413
- Classification: CI coverage gap.
- Problem: `.github/workflows/build.yml` `push.paths` and `pull_request.paths`
  listed `sdk/**`, `sdk-testing/**`, `sdk-integration-tests/**`, `examples/**`,
  and `pom.xml`, but not `insight-plugin/**`. A change touching only the
  insight plugin would not trigger the build/test workflow.
- Resolution: added `- 'insight-plugin/**'` to **both** the `push.paths` and
  `pull_request.paths` lists (only that entry; no unrelated `otel-plugin`
  additions). YAML validated with `yaml.safe_load`.

---

## Verification

- `mvn spotless:apply` then `mvn -B -q spotless:check --file pom.xml`: clean.
- `.github/workflows/build.yml` parsed with `yaml.safe_load`: valid.
- `mvn -pl insight-plugin -am clean verify`: **BUILD SUCCESS** —
  Tests run: 57, Failures: 0, Errors: 0, Skipped: 0 (was 45; +12 across
  `InputSnapshotTest` (2), `StateCleanupLifecycleTest` (3),
  `UnrecoverableErrorUnwrapTest` (3), `MutableNumberIsolationTest` (4), and the
  updated `WorkflowInsightHookTest` suspend/resume test).
- `mvn clean verify`: full reactor **BUILD SUCCESS** (all 9 modules; 31
  cloud-based integration tests skipped by default).
