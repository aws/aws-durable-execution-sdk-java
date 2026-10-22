# Durable Execution Telemetry Exporter

Instrumentation plugin for the AWS Lambda Durable Execution Java SDK that emits a curated,
per-execution **Durable Execution Telemetry record** to one or more pluggable exporters. It ports the
JavaScript `durableExecutionTelemetry()` contract (canonical record schema `1.0`) to the Java plugin hook
surface.

## Usage

```java
DurableConfig config = DurableConfig.builder()
    .withPlugins(DurableExecutionTelemetry.durableExecutionTelemetry(DurableExecutionTelemetryConfig.builder()
        .samplingRate(1.0)
        .emitMode(DurableExecutionTelemetryConfig.EmitMode.ON_COMPLETE)     // ON_COMPLETE | ON_CHANGE | ON_FAILURE
        .operationDetail(DurableExecutionTelemetryConfig.OperationDetail.TOP_LEVEL) // TOP_LEVEL | FULL_TREE
        .content(ContentConfig.builder()
            .input(true).output(true).includeErrors(true)
            .addOverride(OperationOverride.withResult("compute", r -> r))
            .build())
        .addExporter(S3Exporter.builder().bucket("my-bucket").build())
        .build()))
    .build();
```

## Exporters

Implement `TelemetryExporter` for custom sinks. Every exporter has a builder with one setter per option and a
`maxRecordSizeBytes` override; records over the limit are truncated before export.

| Exporter | Destination | Operations rendering | Default size limit | Artifact (optional) |
|---|---|---|---|---|
| `LambdaLogExporter` | Function log group (stdout) | `operationsByName` | 256,000 bytes (the printed line) | none |
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

### LambdaLogExporter

Writes one line per record to `System.out`, in the Lambda JSON log envelope:

```json
{"timestamp":"2026-10-21T08:00:00.000Z","level":"INFO","message":"<compact record JSON>"}
```

`timestamp` is UTC ISO-8601 with exactly three fraction digits. The keys appear in that order. The
[Lambda Java logging docs](https://docs.aws.amazon.com/lambda/latest/dg/java-logging.html) and the
[Lambda log format docs](https://docs.aws.amazon.com/lambda/latest/dg/monitoring-cloudwatchlogs-logformat.html)
list `LambdaLogger` and Log4j2 as the Java methods that Lambda captures as structured JSON, so `System.out` is not
wrapped and the exporter writes the envelope itself. Lambda does not double-encode logs that are already JSON, so the
line reaches CloudWatch Logs as written.

The size limit bounds the printed line, envelope included: the exporter's `render` returns the envelope, so the one
truncation pass measures exactly the line that is printed. The default limit is 256,000 bytes; CloudWatch Logs accepts
log events of up to 1 MB. `message` is the record JSON escaped again, so a record with many `"` or `\` characters can
grow to about twice its size. The exporter computes the line's size without building it (the envelope's fixed 68 bytes
plus the record JSON's size once escaped again), so the size checks stay cheap on large records. Truncation drops
error causes first, then operation results, whole operations, input and output (see
[Error names and causes](#error-names-and-causes) and [Design](#design)).

The execution `error` and the identity fields are never dropped, so they set the smallest line truncation can reach.
Every error `name` and `message` is capped at 8,192 code points. On the line, one code point takes at most 7 bytes (a
control character is written as `\u00XX` in the record and its backslash is escaped again), so a capped name or message
takes at most about 57 KB, and about 33 KB when it is all quotes, backslashes or emoji. With identity fields of the
lengths Lambda allows (each a few hundred characters at most):

- the line fits any limit of 128 KB or more, including the default, whatever the error's name and message;
- the line fits 64 KB when the execution error's name is under 500 characters, whatever its message.

An execution error whose name and message are both 8,192 escape-heavy characters makes a line of about 66 KB to 115 KB.
With a limit smaller than the minimal line (for example a limit below the 68-byte envelope) the line is still printed,
over the limit, with `truncated` and the `dropped*` markers set.

Lines have an explicit `"level":"INFO"`. Lambda already treats a line without a level as INFO, so log level filtering
is unchanged: with the JSON log format and an application log level of `WARN` or above, Lambda drops these lines, as
it dropped the raw record lines before. What changed for readers is the line itself: the record is the JSON string in
`message`, so queries and metric filters must parse `message` instead of matching top-level record fields.

`LambdaLogExporter.render` now returns the envelope map (`timestamp`, `level`, `message`) instead of the record map;
`message` is the record JSON string. Code that called `render` to get the `operationsByName` record should call
`DurableExecutionTelemetryRecord.toByNameWireMap()` instead.

### DynamoDBExporter

Table keyed by `pk` (string), optionally with sort key `sk`. IAM: `dynamodb:PutItem`.

```java
DynamoDBExporter.builder().tableName("durable-execution-telemetry").build()          // history: pk = ARN, sk = emittedAt
DynamoDBExporter.builder().tableName("durable-execution-telemetry").sortKey("").build() // upsert: pk only
```

### AuroraExporter

Cluster with the Data API enabled. Time values are bound as ISO-8601 strings. Table columns: `execution_arn
VARCHAR(512) PRIMARY KEY`, `execution_name VARCHAR(256)`, `function_name VARCHAR(128)`, `status VARCHAR(20)`,
`start_time VARCHAR(30)`, `end_time VARCHAR(30)`, `duration_ms BIGINT`, `record_json` (`JSONB` on PostgreSQL,
`LONGTEXT` on MySQL), `emitted_at VARCHAR(30)`. On PostgreSQL the time columns may instead be `TIMESTAMPTZ`; the
statement casts the values. IAM: `rds-data:ExecuteStatement`, `secretsmanager:GetSecretValue`.

```java
AuroraExporter.builder()
    .resourceArn(clusterArn).secretArn(secretArn).database("telemetry")
    .engine(AuroraExporter.Engine.POSTGRESQL)   // or MYSQL
    .build()
```

### RedshiftExporter

Serverless workgroup or provisioned cluster. Same columns as Aurora with `record_json SUPER` and `TIMESTAMPTZ` time
columns; rows are upserted with `MERGE`. IAM: `redshift-data:ExecuteStatement` plus `redshift-serverless:GetCredentials`
(Serverless) or `secretsmanager:GetSecretValue` / `redshift:GetClusterCredentialsWithIAM` (provisioned).

```java
RedshiftExporter.builder().workgroupName("telemetry").database("dev").build()
RedshiftExporter.builder().clusterIdentifier("my-cluster").database("dev").secretArn(secretArn).build()
```

### OpenSearchExporter

Domain endpoint; the index is created on first write. The document id is the execution ARN. IAM (SigV4):
`es:ESHttpPut` on `domain/<name>/durable-execution-telemetry/*`.

```java
OpenSearchExporter.builder().endpoint("https://my-domain.us-east-1.es.amazonaws.com").region("us-east-1").build()
OpenSearchExporter.builder().endpoint(url).auth(OpenSearchExporter.Auth.BASIC).username(u).password(p).build()
```

### FirehoseExporter

Delivery stream with any destination. IAM: `firehose:PutRecord`.

```java
FirehoseExporter.builder().deliveryStreamName("durable-execution-telemetry").build()
```

### EventBridgeExporter

Default bus or a custom bus. `DetailType` is the record status, so rules can match `FAILED`. IAM: `events:PutEvents`.

```java
EventBridgeExporter.builder().build()                        // default bus, source software.amazon.lambda.durable.telemetry
EventBridgeExporter.builder().eventBusName("telemetry-bus").build()
```

### SQSExporter

Standard or FIFO queue; FIFO queues receive a group id (execution ARN) and a deduplication id. IAM: `sqs:SendMessage`.

```java
SQSExporter.builder().queueUrl("https://sqs.us-east-1.amazonaws.com/123456789012/telemetry.fifo").build()
```

### OTelExporter

Any OTLP/HTTP logs endpoint; authenticate with headers. `http/protobuf` is not supported. No IAM.

```java
OTelExporter.builder().endpoint("https://otlp.example.com/v1/logs").headers(Map.of("x-api-key", key)).build()
```

### HttpExporter

Any endpoint accepting JSON. `timeoutMs` defaults to 10000. No IAM.

```java
HttpExporter.builder().url("https://hooks.example.com/telemetry").method(HttpExporter.Method.PUT).build()
```

### FileExporter

A writable directory such as an EFS mount or `/tmp`. `NDJSON` appends `{date}.ndjson`; `JSON` writes
`{executionName}.json`.

```java
FileExporter.builder().directory("/mnt/efs/durable-execution-telemetry").mode(FileExporter.Mode.JSON).build()
```

### S3Exporter and CloudWatchLogsExporter

`S3Exporter` writes `{prefix}{partition}{executionName}.json` (IAM: `s3:PutObject`). `CloudWatchLogsExporter` writes
one event per record to `{logStreamPrefix}YYYY/MM/DD` (IAM: `logs:CreateLogStream`, `logs:PutLogEvents`).

## Error names and causes

Every error in a record (the execution `error`, each `operations[].error`, and each
`operationsByName.<name>.error`) has this shape, with keys in this order:

```json
"error": {
  "name": "<the error's type name, as this SDK reports it>",
  "message": "<the error's message>",
  "cause": { "name": "...", "message": "...", "cause": { ... } }
}
```

`cause` is present only when the error has one. It comes from `Throwable.getCause()`, has the same shape, is cut
after 16 levels, and stops at a cause that was already visited, so a cycle cannot loop. Levels are written exactly as
`getCause()` returns them; a level that repeats the one above is not skipped. When an SDK wrapper carries a
checkpointed `ErrorObject`, `name` and `message` come from the `ErrorObject` and the chain still follows
`getCause()`, so a live cause behind the wrapper (for example the real failure behind a checkpoint continuation
failure) is kept. Reading a cause never throws: if a cause's `getCause()`, name or message fails, the chain ends there
and the record is kept. If the top-level error's `getMessage()` fails, its `message` is written as `null` and its
`name` is the class's simple name; the record is kept. Java names and messages are always strings (`message` is `null` when `getMessage()` is), and
`getCause()` always returns a `Throwable`, so the shared rule for causes that are not error objects does not arise in
this SDK.

Every `name` and `message`, on errors and on causes, is capped at 8,192 characters (Unicode code points), so a cut
never splits a surrogate pair. A longer value keeps its first 8,192 code points followed by `…`. When a record is over an exporter's size limit, truncation first removes
every `cause` from every error and sets `droppedCauses: true`, then runs the other truncation steps.

Which errors carry a cause in this SDK:

- **Only the execution-level `error`.** It is the live `Throwable` the handler ended with.
- **Never `operations[].error` or `operationsByName.<name>.error`.** The SDK builds each operation error from the
  operation's checkpoint, which stores only the error type, message and stack trace, so there is no cause to write.
  This holds for the invocation where the operation failed and for every replay.
- **A rebuilt cause can be named `Throwable`.** When a step throws an exception that has a cause, the SDK checkpoints
  the exception and later rebuilds it; the rebuilt cause is a `java.lang.Throwable` carrying the original message,
  not an instance of the original class. For example, a step that throws
  `new RuntimeException("boom", new IOException("io-detail"))` and fails the execution gives
  `{"name":"RuntimeException","message":"boom","cause":{"name":"Throwable","message":"io-detail"}}`. Do not rely on
  `error.cause.name` to find the original cause type.

`includeErrors(false)` removes errors together with their causes. With `includeErrors(true)`, cause messages are
written unredacted, up to 16 of them per error, and there is no transform for errors. Library exceptions often put
hostnames, file paths, SQL or request identifiers in their messages; use `includeErrors(false)` if that is a concern.

Error names are each language's own type names, so they differ across SDKs. For example, a step that throws
`PaymentError` can be reported as `StepError` in JS, `aws_durable_execution_sdk_python.exceptions.StepError` or
`PaymentError` in Python, and `com.example.PaymentError` or `PaymentError` in Java. In Java, an operation error's
`name` is the fully qualified class name stored in the checkpoint (`com.example.PaymentError`), while the
execution-level `error` uses the simple class name of the exception the handler ended with (`PaymentError`). When the
SDK cannot rebuild the step's exception and throws its `StepFailedException` fallback instead, the execution-level
name is the checkpointed fully qualified name. To compare failures across SDKs, query per SDK, or query on `message`
and the `cause` chain.

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
  `includeErrors(false)` neither is emitted, nor its `cause` chain, so a sensitive failure message never reaches a
  record.
- **Non-fatal plugin failures are isolated.** Record construction, snapshotting, transforms,
  truncation and exporter render/export/flush contain and log ordinary failures, including
  `AssertionError` and optional-dependency `NoClassDefFoundError`. `VirtualMachineError` and
  `ThreadDeath` propagate as their original error objects, including through future, reflection
  and Jackson transport wrappers. Arbitrary business-exception causes are not reclassified.
  A fatal failure stops the shared scheduler: queued records are released and drain/flush waiters
  fail, including when another exporter is blocked. Subsequent scheduler calls rethrow that fatal;
  a finished fatal pump cannot later be mistaken for a successful drain or flush. Already-running
  customer exporter code cannot be forcibly stopped, but it does not delay fatal delivery.
- **Per-exporter size truncation** (`Truncation`) is one pass that drops, in order: every error
  `cause`, then operation results oldest-first, then whole operations oldest-first, then execution
  input, then output — setting `truncated`, `droppedCauses`, `droppedOperations`, `droppedInput`,
  `droppedOutput` as applicable. Markers already on a record are kept. The size is measured against
  the exact value each exporter emits (its `render`). `LambdaLogExporter` computes that size without
  building the line, and the pass skips checks that cannot succeed yet (each operation's largest
  possible saving is sized once), so the cost grows with the record rather than with the number of
  dropped operations times the record. The execution `error` and identity fields are never dropped.
- **Exporter isolation.** Every exporter receives its own copy of each record, truncated to its own
  limit; ordinary exporter failures are logged without preventing the other exporters from running.
- **Export scheduling.** Exporter I/O never runs on the SDK threads that deliver plugin hooks.
  Records are handed to a background worker that exports at most one record at a time; each
  record is a complete snapshot of its execution, so while an export is in flight newer updates for
  the same invocation coalesce into that invocation's pending slot and only the latest is exported
  next; records of different invocations never displace each other. At invocation end the plugin waits
  for its latest record to reach every exporter, then requests a flush through the same worker.
  Concurrent flush requests may share one flush, and the final record is delivered before the
  invocation returns.

## Conformance

Validated against the Durable Execution Telemetry conformance suite behaviors `telemetry-1 … telemetry-18`
(PR #73 Java examples). See the module tests for the behavior mapping.
