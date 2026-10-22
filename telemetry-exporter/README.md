# Durable Execution Telemetry Exporter (preview)

Instrumentation plugin for the AWS Lambda Durable Execution Java SDK that emits a curated,
per-execution **Durable Execution Telemetry record** to one or more pluggable exporters. It ports the
JavaScript `durableExecutionTelemetry()` contract (canonical record schema `1.0`) to the Java plugin hook
surface.

> **Preview API.** Every public type is annotated
> `@software.amazon.lambda.durable.annotations.Experimental` to signal it is experimental and may
> change or be removed in a future release without a major-version bump.

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
- **`includeErrors`** gates only the per-operation errors (`operations[].error` and
  `operationsByName.<name>.error`), as in the JS exporter. **`includeErrors(false)` does not hide
  the execution-level `error`**: its `name` and `message` are emitted on every FAILED record. In
  2.3.0 and earlier, `includeErrors(false)` also suppressed the execution error.
  In Java the execution error is usually the failed operation's own exception rethrown, so its
  message is usually the operation's message too. No setting suppresses the execution error
  message, and the input/output transforms do not apply to errors. Do not put secrets or personal
  data in exception messages that can reach the handler's return path: catch such an exception and
  rethrow one with a safe message, or, in a custom `TelemetryExporter`, remove or redact `error` in
  `render`.
- **`error.name`** follows the cross-SDK rule in [`error.name`](#errorname) below.
- **`LambdaLogExporter` line shape.** Each record is printed to stdout as one Lambda JSON log line,
  `{"timestamp":"<UTC, 3 fraction digits>Z","level":"INFO","message":"<compact record JSON>"}`,
  because the JS readers of this destination filter on `level` and parse `message`. The Node
  runtime adds this envelope around `console.log`; Java `System.out` is not wrapped, and Lambda
  does not re-wrap a line that is already JSON. `maxRecordSizeBytes` is measured on the record
  (`render`), not on the printed line. The record is embedded in `message` as a JSON string, so
  every `"` and `\` in it is escaped again: the line is the record plus 68 bytes plus one byte
  per quote or backslash in the record, and can be up to about twice the record size
  (`2 × maxRecordSizeBytes + 68` in the worst case, about 512 KB with the default 256,000). That
  is within the CloudWatch Logs limit of 1 MB per log event.
  `LambdaLogExporter` lines are INFO; an application log level of WARN or above drops them.
- **Non-fatal plugin failures are isolated.** Record construction, snapshotting, transforms,
  truncation and exporter render/export/flush contain and log ordinary failures, including
  `AssertionError` and optional-dependency `NoClassDefFoundError`. `VirtualMachineError` and
  `ThreadDeath` propagate as their original error objects, including through future, reflection
  and Jackson transport wrappers. Arbitrary business-exception causes are not reclassified.
  A fatal failure stops the shared scheduler: queued records are released and drain/flush waiters
  fail, including when another exporter is blocked. Subsequent scheduler calls rethrow that fatal;
  a finished fatal pump cannot later be mistaken for a successful drain or flush. Already-running
  customer exporter code cannot be forcibly stopped, but it does not delay fatal delivery.
- **Per-exporter size truncation** (`Truncation`) drops, in order: operation results oldest-first,
  then whole operations oldest-first, then execution input, then output — setting `truncated`,
  `droppedOperations`, `droppedInput`, `droppedOutput` as applicable. The size is measured against
  the exact shape each exporter emits (its `render`).
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

## `error.name`

This rule is shared by the JS, Python and Java exporters. `error.message` is never changed by
this rule. The rule applies to the execution-level `error`, every `operations[].error`, and every
`operationsByName.<name>.error`.

1. A record names the error that caused the failure. A wrapper error that the SDK added is never
   the name, except in the bounded cases of rules 3 and 9.
2. The SDK unwraps an error only when the error's type is one of the SDK's own wrapper types. A
   user error is never unwrapped, even if it has a cause.
3. Unwrapping follows the chain of SDK wrappers. The depth counts unwrap steps, one per wrapper
   passed, not levels in the operation tree. At most 16 unwraps happen. If the error reached after
   16 unwraps is still a wrapper, its name is reported. If the chain reaches an error it has
   already visited, the walk stops and the last error visited before the repeat is reported.
4. If the chain ends at a user error, the name is that error's type name.
5. If the chain ends at an SDK error that is not a wrapper, the name is that SDK error's simple
   class name. A callback timeout is an example.
6. A type name that is a qualified string is reduced to its last segment after any `.` or `$`. If
   that segment is empty, the string is kept as it is. A missing type, or a type made only of blank
   characters, is `Error`. The blank characters are the six ASCII whitespace characters: space,
   `\t`, `\n`, `\r`, `\f` and `\v`. No other character counts as blank, and names are never
   trimmed.
7. A failure gets the same name whether the record is built live or from a replayed checkpoint.
8. When the execution fails because an operation's error propagated, the execution `error.name`
   equals that operation's `error.name`.
9. If the SDK cannot tell which of several failures caused a wrapper, it does not guess. It
   reports the wrapper's name. Two map items or parallel branches that fail with the same message
   but different types are an example.

A callback that is failed externally with an explicit `ErrorType` is named after that type, after
the reduction of rule 6. This includes the type `"Error"`, which reports `Error`. Only a missing or
blank type reports the SDK callback-failure class.

User error types have the same name in every SDK, because the user names them. SDK error types
keep each language's own class name. For example, a callback timeout is `CallbackTimeoutError` in
JS and Python and `CallbackTimeoutException` in Java. Every SDK name for a callback timeout
contains `Timeout`. An invoke timeout does not always: Java names it `InvokeTimedOutException`, so
a `/Timeout/` query that should also match invoke timeouts needs `/Timeout|TimedOut/`.

How the Java exporter applies it:

- Rule 6 is applied to `Class.getName()` for live errors and to the checkpointed `errorType` for
  replayed ones, so both give the same name. `com.acme.Outer$PaymentError` gives `PaymentError`;
  a top-level class named `Payment$Error` gives `Error`; a local class `LocalError` declared in
  `pkg.Foo` (binary name `pkg.Foo$1LocalError`) gives `1LocalError`; an anonymous class gives its
  last segment, for example `1`. A string that ends with a separator (`trailing.`, `Outer$`) is
  kept as it is. U+00A0, U+FEFF, U+001C and U+2003 are not blank, so a type made of them is kept.
- A hidden class's name ends in `/0x…`, a suffix that changes on every JVM start. The name is cut
  at the first `/` before rule 6, live and from the checkpoint, so `pkg.Gen$HiddenErr/0x…` gives
  `HiddenErr`.
- The SDK wrappers are an explicit list, matched on the exact runtime class:
  `DurableOperationException`, `UnrecoverableDurableExecutionException`, `StepException`,
  `CallbackException`, `InvokeException`, `StepFailedException`, `CallbackFailedException`,
  `CallbackSubmitterException`, `ChildContextFailedException`, `InvokeFailedException`,
  `MapIterationFailedException`, `ParallelBranchFailedException` and
  `WaitForConditionFailedException`. A subclass of one of them, such as a user exception, is not a
  wrapper and reports its own name. Every other class in `software.amazon.lambda.durable.exception`
  is an SDK error that reports its simple class name: `CallbackTimeoutException`,
  `InvokeTimedOutException`, `InvokeStoppedException`, `StepInterruptedException`,
  `IllegalDurableOperationException`, `NonDeterministicExecutionException`, `SerDesException` and
  `DurableExecutionException`.
- A timed-out callback, and a timed-out or stopped invoke, are SDK errors like the callback
  timeout. The operation and the execution report `CallbackTimeoutException`,
  `InvokeTimedOutException` or `InvokeStoppedException`, whatever error type the checkpoint
  carries. A stopped invoke is not named after its stop error: a stop with `errorType`
  `States.Stopped` or `com.acme.PaymentError` reports `InvokeStoppedException`.
- After 16 unwraps, or at a cycle, the reported wrapper's name is its checkpointed `errorType`,
  reduced; with a blank type it is the SDK failure class below.
- When an operation failed with no error type (for example a callback failed externally without an
  `errorType`), the operation and the execution report the SDK exception the core throws for it,
  such as `CallbackFailedException`.
- When the checkpoint has no error at all, the operation error has the SDK exception's name and a
  `null` message, because there is no original message to keep. This is the case for a timed-out
  callback and a timed-out invoke in the local test runner, and for a stopped invoke stopped
  without an error. Before this change such operations had no `error` object; a reader that tests
  `error != null` now sees one with `"message": null`. The execution error of a timed-out callback
  has the message `Callback timed out: <callbackId>`, built by `CallbackTimeoutException`; it is not
  checkpointed, and the JS and Python exporters report `Callback timed out` without the id.
- Java `map` and `parallel` return item and branch failures as data and do not throw them, so the
  SDK never has to choose between several failures (rule 9). The execution error is whatever the
  handler throws.
- Non-class type strings are reduced too: an external callback failure with `errorType`
  `States.Timeout` reports `Timeout`.

## Conformance

Validated against the Durable Execution Telemetry conformance suite behaviors `telemetry-1 … telemetry-18`
(PR #73 Java examples). See the module tests for the behavior mapping.
