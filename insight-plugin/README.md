# Workflow Insight Plugin (preview)

Instrumentation plugin for the AWS Lambda Durable Execution Java SDK that emits a curated,
per-execution **Workflow Insight record** to one or more pluggable exporters. It ports the
JavaScript `workflowInsight()` contract (canonical record schema `1.0`) to the Java plugin hook
surface.

> **Preview API.** Every public type is annotated `@Deprecated` to signal it is experimental and
> may change or be removed in a future release.

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
`CloudWatchLogsExporter` (PutLogEvents to a specific log group, `operationsByName` map). Implement
`InsightExporter` for custom sinks.

## Design

- **Snapshot-based, not accumulated.** Each record is built directly from the current-invocation
  operation snapshot the SDK provides — `InvocationInfo.operations()` at start / operation change
  and `InvocationEndInfo.operations()` at end. Execution input/output come from
  `InvocationInfo.executionInput()` / `InvocationEndInfo.executionResult()`, and per-operation
  results from `OperationChangeItemInfo.result()` (all surfaced by SDK PR #618). There is no global
  "current ARN" or cross-hook operation accumulation.
- **Per-execution state keyed by execution ARN** holds only the stable start time, parsed ARN,
  cached input, and the one-time deterministic sampling decision. State is **preserved across
  non-terminal (PENDING/RETRYING) invocations** so suspend/resume keeps a single stable start time
  and correct duration, and is removed **only** once the execution is terminal.
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
- **Per-exporter size truncation** (`Truncation`) drops, in order: operation results oldest-first,
  then whole operations oldest-first, then execution input, then output — setting `truncated`,
  `droppedOperations`, `droppedInput`, `droppedOutput` as applicable. The size is measured against
  the exact shape each exporter emits (its `render`).
- **Exporter isolation.** Every exporter is truncated, exported, and flushed independently; a
  failing exporter is logged and never blocks the others or the execution.

## Conformance

Validated against the Workflow Insight conformance suite behaviors `insight-1 … insight-18`
(PR #73 Java examples). See the module tests for the behavior mapping.
