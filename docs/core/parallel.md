## parallel() – Concurrent Branch Execution

`parallel()` runs multiple independent branches concurrently, each in its own child context. Branches are registered via `branch()` and execute immediately (respecting `maxConcurrency`). The operation completes when all branches finish or completion criteria are met.

```java
// Basic parallel execution
var parallel = ctx.parallel("validate-and-process");
DurableFuture<Boolean> task1 = parallel.branch("validate", Boolean.class, branchCtx -> {
    return branchCtx.step("check", Boolean.class, stepCtx -> validate());
});
DurableFuture<String> task2 = parallel.branch("process", String.class, branchCtx -> {
    return branchCtx.step("work", String.class, stepCtx -> process());
});

// Wait for all branches and get the aggregate result
ParallelResult result = parallel.get();

// Access individual branch results
Boolean validated = task1.get();
String processed = task2.get();
```

`ParallelDurableFuture` implements `AutoCloseable` — calling `close()` triggers `get()` if it hasn't been called yet, ensuring all branches complete.

```java
// AutoCloseable pattern
try (var parallel = ctx.parallel("work")) {
    parallel.branch("a", String.class, branchCtx -> branchCtx.step("a1", String.class, stepCtx -> "a"));
    parallel.branch("b", String.class, branchCtx -> branchCtx.step("b1", String.class, stepCtx -> "b"));
} // close() calls get() automatically
```

### ParallelResult

`ParallelResult` is a summary of the parallel execution:

| Field | Description |
|-------|-------------|
| `size()` | Total number of registered branches |
| `succeeded()` | Number of branches that succeeded |
| `failed()` | Number of branches that failed |
| `skipped()` | Number of branches recorded as `SKIPPED` |
| `statuses()` | Per-branch status: `SUCCEEDED`, `FAILED`, or `SKIPPED` |
| `completionStatus()` | Why the operation completed (`ALL_COMPLETED`, `MIN_SUCCESSFUL_REACHED`, `FAILURE_TOLERANCE_EXCEEDED`) |

When a completion config finishes the parallel operation early, branches that never started — and branches that were still running at completion — are recorded as `SKIPPED`. The snapshot and the skip happen together with that branch's own completion, so `get()` does not return a value and later throw for the same call sequence. `get()` on a skipped branch throws `ParallelBranchSkippedException` on the first invocation and on every replay. The SDK does not start or resume that branch later.

`DurableFuture.anyOf` does not select a skipped branch while another future can still settle, so the winner from the first invocation is the winner on replay. If every future passed to `anyOf` is skipped, it throws `ParallelBranchSkippedException`.

### ParallelConfig

Configure concurrency limits and completion criteria:

```java
var config = ParallelConfig.builder()
    .maxConcurrency(5)                                    // at most 5 branches run at once
    .completionConfig(CompletionConfig.allCompleted())    // default: run all branches
    .build();

var parallel = ctx.parallel("work", config);
```

| Option | Default | Description |
|--------|---------|-------------|
| `maxConcurrency` | Unlimited | Maximum branches running simultaneously (must be ≥ 1) |
| `completionConfig` | `allCompleted()` | Controls when the operation stops starting new branches |

#### CompletionConfig

`CompletionConfig` controls when the parallel operation stops starting new branches:

| Factory Method | Behavior |
|----------------|----------|
| `allCompleted()` (default) | All branches run regardless of failures |
| `allSuccessful()` | Stop if any branch fails (zero failures tolerated) |
| `firstSuccessful()` | Stop after the first branch succeeds |
| `minSuccessful(n)` | Stop after `n` branches succeed |
| `toleratedFailureCount(n)` | Stop after more than `n` failures |

Note: `toleratedFailurePercentage` is not supported for parallel operations.

### ParallelBranchConfig

Per-branch configuration can be provided:

```java
parallel.branch("work", String.class, branchCtx -> doWork(),
    ParallelBranchConfig.builder()
        .serDes(customSerDes)
        .build());
```

### Error Handling

Branch failures are captured individually. A failed branch throws its exception when you call `get()` on its `DurableFuture`:

```java
var parallel = ctx.parallel("work");
var risky = parallel.branch("risky", String.class, branchCtx -> {
    throw new RuntimeException("failed");
});
var safe = parallel.branch("safe", String.class, branchCtx -> {
    return branchCtx.step("ok", String.class, stepCtx -> "done");
});

ParallelResult result = parallel.get();

String safeResult = safe.get();  // "done"
try {
    risky.get();  // throws
} catch (ParallelBranchFailedException e) {
    // Branch failed and the SDK could not reconstruct the original exception.
    // This happens when: the error info was not checkpointed, the exception
    // class is not on the classpath, or deserialization of the error data
    // failed. The original error type and message are in e.getMessage().
}
```

A skipped branch is different: the parallel result recorded it as `SKIPPED`, so `get()` throws `ParallelBranchSkippedException` instead of waiting:

```java
var parallel = ctx.parallel("first-successful", ParallelConfig.builder()
        .maxConcurrency(1)
        .completionConfig(CompletionConfig.firstSuccessful())
        .build());
var a = parallel.branch("branch-a", String.class, branchCtx -> "A");
var b = parallel.branch("branch-b", String.class, branchCtx -> "B");
parallel.get(); // statuses [SUCCEEDED, SKIPPED]
a.get();        // "A"
try {
    b.get();    // throws on the first invocation and on every replay
} catch (ParallelBranchSkippedException e) {
    // Branch never started, or was still running when the parallel completed.
    // e.getBranchName() is the name passed to branch().
}
```

| Exception | When Thrown |
|-----------|-------------|
| `ParallelBranchFailedException` | Branch failed and the original exception could not be reconstructed |
| `ParallelBranchSkippedException` | Branch was recorded as `SKIPPED` in the parallel result |
| User's exception | Branch threw a reconstructable exception — propagated through `get()` |

### Checkpoint-and-Replay

Parallel operations are fully durable. On replay after interruption:

- Completed branches return cached results without re-execution
- Incomplete branches resume from their last checkpoint
- Branches recorded as `SKIPPED` are not started; `get()` on those branch futures throws `ParallelBranchSkippedException`
