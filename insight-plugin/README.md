# Workflow Insight Plugin (preview)

Instrumentation plugin for the AWS Lambda Durable Execution Java SDK that emits a curated,
per-execution **Workflow Insight record** to one or more pluggable exporters. It ports the
JavaScript `workflowInsight()` contract (canonical record schema `1.0`) to the Java plugin hook
surface.

> **Preview API.** Every public type is annotated
> `@software.amazon.lambda.durable.annotations.Experimental` to signal it is experimental and may
> change or be removed in a future release without a major-version bump.

## Usage

```java
DurableConfig config = DurableConfig.builder()
    .withPlugins(WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
        .samplingRate(1.0)
        .emitMode(WorkflowInsightConfig.EmitMode.ON_COMPLETE)     // ON_COMPLETE | ON_CHANGE | ON_FAILURE
        .operationDetail(WorkflowInsightConfig.OperationDetail.TOP_LEVEL) // TOP_LEVEL | FULL_TREE
        .content(ContentConfig.builder()
            .input(true).output(true).includeErrors(true)
            .addOverride(OperationOverride.withResult("compute", r -> r))
            .build())
        .addExporter(S3Exporter.builder().bucket("my-bucket").build())
        .build()))
    .build();
```

Exporters: `LambdaLogExporter` (default; writes the `operationsByName` map to stdout →
CloudWatch), `S3Exporter` (canonical `operations` array, one object per execution),
`CloudWatchLogsExporter` (PutLogEvents to a specific log group, `operationsByName` map),
`HttpExporter` (POST/PUT each record to any HTTP(S) endpoint or webhook, choice of operations shape).
Implement `InsightExporter` for custom sinks.

`LambdaLogExporter` and `HttpExporter` need no extra dependency — the HTTP exporter uses the JDK's own
HTTP client. The AWS SDK service modules used by the remote exporters are optional so applications
that use only Lambda logs or HTTP do not package them. Add the module for each remote exporter you
configure, using the AWS SDK for Java 2.x version managed by your application:

```xml
<!-- Required only for S3Exporter -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>AWS_SDK_VERSION</version>
</dependency>

<!-- Required only for CloudWatchLogsExporter -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>cloudwatchlogs</artifactId>
    <version>AWS_SDK_VERSION</version>
</dependency>
```

### HttpExporter

`HttpExporter` POSTs (or PUTs) each record to any HTTP(S) endpoint or webhook as a JSON body with
`Content-Type: application/json`. It uses the JDK's built-in HTTP client, so it adds **no dependency**.
It mirrors the JS `HttpExporter`.

```java
.addExporter(HttpExporter.builder()
    .url("https://collector.example.com/workflow-insight")   // required, absolute http/https URL
    .method(HttpExporter.Method.POST)                        // POST (default) or PUT
    .addHeader("Authorization", "Bearer " + token)           // optional auth / API-key headers
    .timeoutMs(10_000)                                       // per-request timeout, default 10s
    .operationsFormat(HttpExporter.OperationsFormat.ARRAY)   // ARRAY (default) | BY_NAME | BOTH
    .build())
```

Behavior:

- **Endpoint / method / headers.** Records are sent to `url` with `method` (POST or PUT — use PUT for
  endpoints that upsert by URL path). Your headers are merged on top of the fixed
  `Content-Type: application/json`.
- **Auth.** There is no built-in auth scheme; pass whatever your endpoint expects as a header
  (`Authorization: Bearer …`, `x-api-key: …`, etc.) via `addHeader`/`headers`.
- **Timeout.** `timeoutMs` (default 10000) bounds the whole request; a slow or unreachable endpoint
  fails fast instead of stalling the invocation. As with every exporter, a failure here is logged and
  isolated — it never disrupts the durable execution or the other exporters.
- **Success / failure.** A 2xx response is success; any other status throws (and is contained by the
  plugin), so a misconfigured endpoint is visible in logs.
- **Operations shape.** `operationsFormat` selects what the body carries: `ARRAY` (the canonical
  `operations` array, default), `BY_NAME` (the name-keyed `operationsByName` map), or `BOTH`.
- **Size cap.** `maxRecordSizeBytes` has no default — a generic HTTP endpoint has no known limit. Set
  it only if your endpoint caps request size; truncation is then measured against the shape actually
  sent.
- **Testing seam.** For unit tests you can inject an `HttpSender` via `.sender(...)` instead of making
  a real network call, or point `url` at a local in-process server.

## Design

- **Snapshot-based, not accumulated.** Each record is built directly from the current-invocation
  operation snapshot the SDK provides — `InvocationInfo.operations()` at start / operation change
  and `InvocationEndInfo.operations()` at end. Execution input/output come from
  `InvocationInfo.executionInput()` / `InvocationEndInfo.executionResult()`, and per-operation
  results from `OperationChangeItemInfo.result()` (all surfaced by SDK PR #618). There is no global
  "current ARN" or cross-hook operation accumulation.
- **Per-execution state keyed by execution ARN** holds the stable start time, parsed ARN, cached
  input, and deterministic sampling decision for the current invocation. State is removed after
  every invocation end, including PENDING/RETRYING, and recreated from stable hook data when the
  execution resumes.
- **Deterministic sampling.** FNV-1a-32 over the execution ARN mapped into `[0,1)`, identical to
  the JS implementation, so a resumed execution always reaches the same in/out decision.
- **Emission modes.** `ON_COMPLETE` emits one terminal record; `ON_FAILURE` emits only on terminal
  failure; `ON_CHANGE` emits at invocation start, on every operation change, and at invocation end
  (matching JS). Non-terminal statuses map to `RUNNING`.
- **Operation filtering** mirrors JS: the `EXECUTION` pseudo-operation and unnamed operations are
  dropped; `TOP_LEVEL` detail drops any operation with a `parentId`; an `OperationOverride.exclude`
  drops by name. Operation `result` is included only when an `OperationOverride.withResult`
  transform opts in — the checkpointed JSON is parsed before the transform, falling back to the raw
  string, and a throwing transform omits the field.
- **Content transforms receive detached, JSON-compatible values.** The `input`/`output` transforms
  and an `OperationOverride.withResult` transform never receive the SDK's original Java object: a
  POJO is presented as a `Map`, a list as a `List`, and a Java-time type as its JSON representation
  (e.g. an `Instant` arrives as an ISO-8601 `String`). This is the maintainer's minimum unblock —
  it is deliberately *not* a type-preserving clone. Mutating the argument is safe (it cannot corrupt
  the cached input snapshot or any later emission), and a transform that throws omits the field and
  logs the failure rather than silently dropping it or failing the execution.
- **`includeErrors`** gates **both** the execution-level error and each operation-level error; with
  `includeErrors(false)` neither is emitted, so a sensitive failure message never reaches a record.
- **Plugin failures never disrupt execution.** Every plugin-owned boundary — record construction,
  input snapshotting, transforms, truncation, and each exporter's render/export/flush (including a
  `NoClassDefFoundError` from an optional exporter's absent SDK) — is guarded against any `Throwable`
  and logged, so one failing exporter cannot block the others and no plugin fault propagates into
  the durable execution.
- **Per-exporter size truncation** (`Truncation`) drops, in order: operation results oldest-first,
  then whole operations oldest-first, then execution input, then output — setting `truncated`,
  `droppedOperations`, `droppedInput`, `droppedOutput` as applicable. The size is measured against
  the exact shape each exporter emits (its `render`).
- **Exporter isolation.** Every exporter is truncated, exported, and flushed independently; a
  failing exporter is logged and never blocks the others or the execution.

## Conformance

Validated against the Workflow Insight conformance suite behaviors `insight-1 … insight-18`
(PR #73 Java examples). See the module tests for the behavior mapping.
