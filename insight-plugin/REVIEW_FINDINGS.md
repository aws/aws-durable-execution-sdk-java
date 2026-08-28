# PR #661 — Codex Review Findings & Resolution

Workflow Insight plugin (`insight-plugin`). Review run by Codex AI on commit
`9cb8201` of branch `workflow-insight-plugin`.

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
| 7 | `arf_v1_rzwdoyquezpr2qgby63imtmxcn` | P2 | Prevent one exporter mutating later exporters' records | FIXED |
| 8 | `arf_v1_47kz274xvx4sow4mmbm7gzor7a` | P3 | Normalize the hash into the half-open interval | DEFERRED |

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
- Classification: wire-shape fidelity.
- Problem: an enabled-but-`null` value is conflated with content excluded by
  configuration; the wire map omits both. A successful handler returning `null`
  therefore has no `output` field even though output inclusion defaults to true.
- Disposition: left OPEN. The fix (track field presence separately or use an
  explicit JSON-null sentinel, emitting `null` for included terminal values
  while omitting only unavailable/disabled content, plus null input/output
  tests) touches the record's presence/omission model and its cross-SDK wire
  contract; deferred to a dedicated change. Not addressed here.

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
- Caveat: nested `Map`/`List` structure is deep-copied (the mutation vector for
  JSON payloads); arbitrary user POJO leaves are shared as immutable values. If
  a payload is a mutable custom POJO, a hostile exporter could still mutate its
  internal fields — considered out of scope and unlikely for
  serialized/deserialized payloads.

## 8 — Normalize the hash into the half-open interval — `DEFERRED` (P3)

- Discussion: https://github.com/aws/aws-durable-execution-sdk-java/pull/661#discussion_r3883201548
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

---

## Verification

- `mvn spotless:apply` then `mvn -B -q spotless:check --file pom.xml`: clean.
- `mvn -pl insight-plugin -am clean verify`: **BUILD SUCCESS** —
  Tests run: 45, Failures: 0, Errors: 0, Skipped: 0.
- `mvn -q clean verify`: full reactor **BUILD SUCCESS**.
- `bash -n .github/scripts/maven_publish.sh`: clean.
