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
`SQSExporter` (one `SendMessage` per emission; standard or FIFO queue). Implement
`InsightExporter` for custom sinks.

`LambdaLogExporter` needs no extra dependency. The AWS SDK service modules used by the remote
exporters are optional so applications that use only Lambda logs do not package them. Add the module
for each remote exporter you configure, using the AWS SDK for Java 2.x version managed by your
application:

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

<!-- Required only for SQSExporter -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>sqs</artifactId>
    <version>AWS_SDK_VERSION</version>
</dependency>
```

## SQSExporter

Sends one SQS `SendMessage` per emission, with the full record JSON as the message body. Works with
standard and FIFO queues. **Resource:** an SQS queue (standard or FIFO). **IAM:** `sqs:SendMessage`
on the target queue.

```java
.addExporter(SQSExporter.builder()
    .queueUrl("https://sqs.us-east-1.amazonaws.com/123456789012/workflow-insight")
    .region("us-east-1")                                  // optional; defaults to the SDK chain
    .operationsFormat(SQSExporter.OperationsFormat.ARRAY) // ARRAY (default) | BY_NAME | BOTH
    .build())
```

- **`queueUrl`** (required) — the target queue URL. A URL ending in `.fifo` is treated as a FIFO
  queue.
- **`messageGroupId`** (optional, FIFO only) — defaults to the record's `executionArn`, so all
  messages for one execution stay ordered together. Bounded to SQS's 128-character limit (hashed
  when longer; see FIFO behavior below). Ignored for standard queues.
- **`region`** (optional) — region for the created client; ignored when a client is injected.
- **`operationsFormat`** (optional) — `ARRAY` (canonical `operations` array, default), `BY_NAME`
  (the `operationsByName` map replacing the array), or `BOTH` (the array plus the map).
- **`maxRecordSizeBytes`** (optional) — defaults to `256_000` (SQS's 256 KB message limit). Must be
  positive; a non-positive value is rejected at build time. This bounds the rendered JSON **body**
  only. SQS counts message attributes toward the same 256 KB message quota, so the default is not
  set to the exact quota — leave headroom below the quota for the `status` and `functionName`
  attributes when tuning it.

**FIFO behavior:** on a `.fifo` queue the exporter sets `MessageGroupId` (see above) and a
`MessageDeduplicationId`. SQS caps both ids at 128 characters. The group id is used verbatim when
it already fits and is otherwise replaced by a deterministic SHA-256 hex digest of the raw value,
so grouping stays stable per execution. The deduplication id is a SHA-256 hex digest over the
execution ARN, the emitted timestamp, and the rendered body: an exact redelivery of the same
emission produces the same id and is de-duplicated, while two distinct rapid `ON_CHANGE` snapshots
of the same execution produce different ids and are not silently dropped inside SQS's 5-minute
dedup window. Hashing uses only the JDK (`java.security.MessageDigest`), no extra dependency. Every
message carries `status` and `functionName` string message attributes for consumer-side filtering.

**Queue setup:**

```bash
# Standard queue
aws sqs create-queue --queue-name workflow-insight-queue

# FIFO queue (content-based dedup off — the exporter supplies dedup ids)
aws sqs create-queue --queue-name workflow-insight-queue.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=false
```

**IAM policy:**

```json
{
    "Effect": "Allow",
    "Action": "sqs:SendMessage",
    "Resource": "arn:aws:sqs:*:*:workflow-insight-queue*"
}
```

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
