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

## Exporters

Implement `InsightExporter` for custom sinks. Every exporter has a builder with one setter per option and a
`maxRecordSizeBytes` override; records over the limit are truncated before export.

| Exporter | Destination | Operations rendering | Default size limit | Artifact (optional) |
|---|---|---|---|---|
| `LambdaLogExporter` | Function log group (stdout) | `operationsByName` | 256 KB | none |
| `CloudWatchLogsExporter` | Any log group, PutLogEvents | `operationsByName` | 256 KB | `cloudwatchlogs` |
| `S3Exporter` | One object per execution | `operations` array | 5 MB | `s3` |
| `DynamoDBExporter` | One item per record (or per execution) | `operationsByName` | 400 KB | `dynamodb` |
| `AuroraExporter` | One row per execution, RDS Data API | `operations` array | 1 MB | `rdsdata` |
| `RedshiftExporter` | One row per execution, Redshift Data API | `operations` array | 1 MB | `redshiftdata` |
| `OpenSearchExporter` | One document per execution | `operations` array | 10 MB | `http-auth-aws`, `auth` |
| `FirehoseExporter` | One NDJSON record, PutRecord | `operationsFormat` | 1 MB | `firehose` |
| `EventBridgeExporter` | One event, PutEvents | `operationsFormat` | 256 KB | `eventbridge` |
| `SQSExporter` | One message, SendMessage | `operationsFormat` | 256 KB | `sqs` |
| `OTelExporter` | OTLP/HTTP JSON log record | `operationsFormat` (body) | 1 MB | none |
| `HttpExporter` | POST or PUT JSON to a URL | `operationsFormat` | none | none |
| `FileExporter` | NDJSON or JSON files in a directory | `operationsFormat` | none | none |

`operationsFormat` is `ARRAY` (default), `BY_NAME`, or `BOTH`.

Artifacts are `software.amazon.awssdk` modules and are optional: add only the ones for the exporters you configure,
at the AWS SDK for Java 2.x version your application manages. A configured exporter whose artifact is missing fails
at first export with a message naming the artifact; the plugin logs it and continues with the other exporters.

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>dynamodb</artifactId> <!-- or s3, cloudwatchlogs, rdsdata, redshiftdata, firehose, eventbridge, sqs -->
    <version>AWS_SDK_VERSION</version>
</dependency>
```

### DynamoDBExporter

Table keyed by `pk` (string), optionally with sort key `sk`. IAM: `dynamodb:PutItem`.

```java
DynamoDBExporter.builder().tableName("workflow-insight").build()          // history: pk = ARN, sk = emittedAt
DynamoDBExporter.builder().tableName("workflow-insight").sortKey("").build() // upsert: pk only
```

### AuroraExporter

Cluster with the Data API enabled. Time values are bound as ISO-8601 strings. Table columns: `execution_arn
VARCHAR(512) PRIMARY KEY`, `execution_name VARCHAR(256)`, `function_name VARCHAR(128)`, `status VARCHAR(20)`,
`start_time VARCHAR(30)`, `end_time VARCHAR(30)`, `duration_ms BIGINT`, `record_json` (`JSONB` on PostgreSQL,
`LONGTEXT` on MySQL), `emitted_at VARCHAR(30)`. On PostgreSQL the time columns may instead be `TIMESTAMPTZ`; the
statement casts the values. IAM: `rds-data:ExecuteStatement`, `secretsmanager:GetSecretValue`.

```java
AuroraExporter.builder()
    .resourceArn(clusterArn).secretArn(secretArn).database("insight")
    .engine(AuroraExporter.Engine.POSTGRESQL)   // or MYSQL
    .build()
```

### RedshiftExporter

Serverless workgroup or provisioned cluster. Same columns as Aurora with `record_json SUPER` and `TIMESTAMPTZ` time
columns; rows are upserted with `MERGE`. IAM: `redshift-data:ExecuteStatement` plus `redshift-serverless:GetCredentials`
(Serverless) or `secretsmanager:GetSecretValue` / `redshift:GetClusterCredentialsWithIAM` (provisioned).

```java
RedshiftExporter.builder().workgroupName("insight").database("dev").build()
RedshiftExporter.builder().clusterIdentifier("my-cluster").database("dev").secretArn(secretArn).build()
```

### OpenSearchExporter

Domain endpoint; the index is created on first write. The document id is the execution ARN. IAM (SigV4):
`es:ESHttpPut` on `domain/<name>/workflow-insight/*`.

```java
OpenSearchExporter.builder().endpoint("https://my-domain.us-east-1.es.amazonaws.com").region("us-east-1").build()
OpenSearchExporter.builder().endpoint(url).auth(OpenSearchExporter.Auth.BASIC).username(u).password(p).build()
```

### FirehoseExporter

Delivery stream with any destination. IAM: `firehose:PutRecord`.

```java
FirehoseExporter.builder().deliveryStreamName("workflow-insight").build()
```

### EventBridgeExporter

Default bus or a custom bus. `DetailType` is the record status, so rules can match `FAILED`. IAM: `events:PutEvents`.

```java
EventBridgeExporter.builder().build()                        // default bus, source aws.durable-execution.insight
EventBridgeExporter.builder().eventBusName("insight-bus").build()
```

### SQSExporter

Standard or FIFO queue; FIFO queues receive a group id (execution ARN) and a deduplication id. IAM: `sqs:SendMessage`.

```java
SQSExporter.builder().queueUrl("https://sqs.us-east-1.amazonaws.com/123456789012/insight.fifo").build()
```

### OTelExporter

Any OTLP/HTTP logs endpoint; authenticate with headers. `http/protobuf` is not supported. No IAM.

```java
OTelExporter.builder().endpoint("https://otlp.example.com/v1/logs").headers(Map.of("x-api-key", key)).build()
```

### HttpExporter

Any endpoint accepting JSON. `timeoutMs` defaults to 10000. No IAM.

```java
HttpExporter.builder().url("https://hooks.example.com/insight").method(HttpExporter.Method.PUT).build()
```

### FileExporter

A writable directory such as an EFS mount or `/tmp`. `NDJSON` appends `{date}.ndjson`; `JSON` writes
`{executionName}.json`.

```java
FileExporter.builder().directory("/mnt/efs/workflow-insight").mode(FileExporter.Mode.JSON).build()
```

### S3Exporter and CloudWatchLogsExporter

`S3Exporter` writes `{prefix}{partition}{executionName}.json` (IAM: `s3:PutObject`). `CloudWatchLogsExporter` writes
one event per record to `{logStreamPrefix}YYYY/MM/DD` (IAM: `logs:CreateLogStream`, `logs:PutLogEvents`).

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
- **Exporter isolation.** Every exporter receives its own copy of each record, truncated to its own
  limit; a failing or slow exporter is logged and never blocks the others or the execution.
- **Export scheduling.** Exporter I/O never runs on the SDK threads that deliver plugin hooks.
  Records are handed to a background worker that exports at most one record at a time; each
  record is a complete snapshot, so while an export is in flight newer updates coalesce into a
  single pending slot and only the latest is exported next. At invocation end the plugin waits
  for the queue to drain and then flushes every exporter once, so the final record is always
  delivered before the invocation returns.

## Conformance

Validated against the Workflow Insight conformance suite behaviors `insight-1 … insight-18`
(PR #73 Java examples). See the module tests for the behavior mapping.
