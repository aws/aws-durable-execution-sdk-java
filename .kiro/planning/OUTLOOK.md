# Java SDK Outlook - Level 2 and Level 3 Features

**Date:** December 8, 2025  
**Status:** Planning

## Overview

This document outlines features to be added in Level 2 (Standard) and Level 3 (Complete) conformance levels, building on the Level 1 (Minimal) foundation.

## Level 2 (Standard) - Required Features

### 1. Configuration System

**Add configuration for operations:**

```java
// Step configuration
public record StepConfig(
    RetryStrategy retryStrategy,
    StepSemantics semantics,
    SerDes serdes
) {
    public static Builder builder() { ... }
}

// Usage
var config = StepConfig.builder()
    .retryStrategy(RetryStrategy.exponentialBackoff())
    .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
    .build();

context.step("critical", String.class, () -> deductInventory(), config);
```

**Configuration types needed:**
- `StepConfig` - retry, semantics, serdes
- `InvokeConfig` - timeout, serdes
- `CallbackConfig` - timeout, heartbeat timeout, serdes
- `MapConfig` - concurrency, completion config, serdes
- `ParallelConfig` - concurrency, completion config, serdes
- `WaitForConditionConfig` - initial state, wait strategy, serdes

### 2. Retry Strategies

**Implement retry logic for steps:**

```java
public interface RetryStrategy {
    RetryDecision shouldRetry(int attempt, Throwable error);
}

public record RetryDecision(
    boolean shouldRetry,
    Duration delay
) {}

// Presets
public class RetryPresets {
    public static RetryStrategy exponentialBackoff() { ... }
    public static RetryStrategy fixedDelay(Duration delay) { ... }
    public static RetryStrategy noRetry() { ... }
}
```

**Features:**
- Exponential backoff with jitter
- Configurable max attempts
- Retryable error types
- Max delay cap

### 3. Step Semantics

**Add execution semantics:**

```java
public enum StepSemantics {
    AT_LEAST_ONCE_PER_RETRY,  // Default - better performance
    AT_MOST_ONCE_PER_RETRY     // Checkpoint START synchronously
}
```

**Implementation:**
- AT_LEAST_ONCE: Async checkpoint START
- AT_MOST_ONCE: Sync checkpoint START (wait for confirmation)

### 4. CALLBACK Operation

**Add callback support:**

```java
// Create callback
var callback = context.createCallback("approval", String.class);
sendApprovalRequest(callback.getCallbackId());
var approval = callback.get();  // Blocks until callback completes

// Wait for callback (combined)
var approval = context.waitForCallback(
    "approval",
    callbackId -> sendApprovalRequest(callbackId),
    String.class
);
```

**Features:**
- Timeout configuration
- Heartbeat timeout
- Success/failure completion
- Callback ID management

### 5. CHAINED_INVOKE Operation

**Add function invocation:**

```java
var result = context.invoke(
    "validate-customer",
    "arn:aws:lambda:us-east-1:123456789012:function:customer-service",
    new ValidateRequest(customerId),
    CustomerData.class
);
```

**Features:**
- Synchronous invocation
- Result deserialization
- Error propagation
- Timeout handling

### 6. Logging Integration

**Add structured logging:**

```java
public interface DurableLogger {
    void debug(String message, Object... args);
    void info(String message, Object... args);
    void warn(String message, Object... args);
    void error(String message, Object... args);
}

// Context-aware logging
context.getLogger().info("Processing order {}", orderId);
```

**Features:**
- SLF4J integration
- MDC context (executionArn, operationId, requestId)
- Replay-aware logging (suppress during replay)

### 7. Enhanced Error Handling

**Add stack trace support:**

```java
record ErrorObject(
    String errorType,
    String errorMessage,
    List<String> stackTrace  // Add stack traces
) {}
```

**Features:**
- Configurable stack trace inclusion
- Better error messages
- Error data serialization

## Level 3 (Complete) - Advanced Features

### 1. CONTEXT Operation (Child Contexts)

**Add child context support:**

```java
var result = context.runInChildContext(
    "batch-processing",
    childCtx -> {
        var step1 = childCtx.step("step1", String.class, () -> "result1");
        var step2 = childCtx.step("step2", String.class, () -> "result2");
        return new Result(step1, step2);
    },
    Result.class
);
```

**Features:**
- Isolated operation IDs
- Nested contexts
- ReplayChildren optimization
- Size limit handling (256KB)

### 2. MAP Operation

**Add concurrent array processing:**

```java
var results = context.map(
    items,
    (ctx, item, index) -> ctx.step("process", String.class, () -> processItem(item)),
    String.class,
    MapConfig.builder()
        .maxConcurrency(5)
        .completionConfig(CompletionConfig.allSuccessful())
        .build()
);
```

**Features:**
- Concurrency control
- Completion policies (minSuccessful, toleratedFailures)
- Item batching
- BatchResult with success/failure tracking

### 3. PARALLEL Operation

**Add concurrent branch execution:**

```java
var results = context.parallel(
    List.of(
        ctx -> ctx.step("task1", String.class, () -> task1()),
        ctx -> ctx.step("task2", String.class, () -> task2()),
        ctx -> ctx.step("task3", String.class, () -> task3())
    ),
    String.class,
    ParallelConfig.builder()
        .maxConcurrency(3)
        .build()
);
```

**Features:**
- Named and unnamed branches
- Concurrency control
- Completion policies
- BatchResult handling

### 4. Wait for Condition

**Add polling with state:**

```java
var result = context.waitForCondition(
    "check-job",
    (state, ctx) -> {
        var status = checkJobStatus(state.jobId);
        return new State(state.jobId, status);
    },
    State.class,
    WaitForConditionConfig.builder()
        .initialState(new State(jobId, "pending"))
        .waitStrategy((state, attempt) -> 
            state.status.equals("complete")
                ? WaitDecision.stop()
                : WaitDecision.continueAfter(Duration.ofSeconds(30))
        )
        .build()
);
```

**Features:**
- State accumulation
- Configurable wait strategies
- Exponential backoff
- Max attempts

### 5. Promise Utilities

**Add promise combinators:**

```java
// All - wait for all to complete
var all = context.promise().all(
    context.stepAsync("task1", String.class, () -> task1()),
    context.stepAsync("task2", String.class, () -> task2())
);

// Race - first to complete
var first = context.promise().race(
    context.stepAsync("task1", String.class, () -> task1()),
    context.stepAsync("task2", String.class, () -> task2())
);

// Any - first to succeed
var any = context.promise().any(
    context.stepAsync("task1", String.class, () -> task1()),
    context.stepAsync("task2", String.class, () -> task2())
);

// AllSettled - wait for all, collect results and errors
var settled = context.promise().allSettled(
    context.stepAsync("task1", String.class, () -> task1()),
    context.stepAsync("task2", String.class, () -> task2())
);
```

**Features:**
- Standard promise patterns
- Works with DurableFuture
- Proper error handling
- Result collection

### 6. Completion Policies

**Add flexible completion criteria:**

```java
public record CompletionConfig(
    Integer minSuccessful,
    Integer toleratedFailureCount,
    Double toleratedFailurePercentage
) {
    public static CompletionConfig allSuccessful() { ... }
    public static CompletionConfig firstSuccessful() { ... }
    public static CompletionConfig allCompleted() { ... }
}
```

**Features:**
- Minimum success threshold
- Failure tolerance (count or percentage)
- Early termination
- Batch result tracking

### 7. BatchResult

**Add result aggregation:**

```java
public class BatchResult<T> {
    private final List<BatchItem<T>> all;
    private final CompletionReason completionReason;
    
    public List<T> getResults() { ... }
    public List<BatchItem<T>> succeeded() { ... }
    public List<BatchItem<T>> failed() { ... }
    public void throwIfError() { ... }
    public int successCount() { ... }
    public int failureCount() { ... }
}

public record BatchItem<T>(
    int index,
    String name,
    BatchItemStatus status,
    T result,
    Throwable error
) {}
```

**Features:**
- Success/failure tracking
- Result extraction
- Error aggregation
- Completion reason

### 8. Advanced Testing

**Enhance test infrastructure:**

```java
// Fake clock for time control
var runner = new LocalDurableTestRunner<>(handler)
    .withFakeClock();

runner.run(event);
runner.advanceTime(Duration.ofHours(1));  // Skip wait time

// Callback simulation
var callback = runner.getCallback("approval");
callback.complete("approved");

// Invoke simulation
runner.registerFunction("customer-service", customerHandler);
```

**Features:**
- Time manipulation
- Callback simulation
- Function registration
- Replay testing

## Implementation Order

### Level 2 Priority Order:
1. Configuration system (foundation for everything)
2. Retry strategies (critical for reliability)
3. Step semantics (important for correctness)
4. CALLBACK operation (common use case)
5. CHAINED_INVOKE operation (common use case)
6. Logging integration (observability)
7. Enhanced error handling (debugging)

### Level 3 Priority Order:
1. CONTEXT operation (foundation for map/parallel)
2. MAP operation (very common use case)
3. PARALLEL operation (very common use case)
4. Wait for condition (common polling pattern)
5. Completion policies (needed by map/parallel)
6. BatchResult (needed by map/parallel)
7. Promise utilities (nice to have)
8. Advanced testing (developer experience)

## Design Considerations

### Thread Pool Reuse
- Level 2/3 operations should reuse the same ExecutorService
- Semaphore for concurrency control in map/parallel
- No additional thread pools needed

### DurableFuture Extensions
- Add methods as needed for promise utilities
- Keep minimal for Level 2
- Expand for Level 3 combinators

### Backward Compatibility
- All Level 2/3 features are additive
- No breaking changes to Level 1 API
- Configuration is optional with sensible defaults

### Performance Optimization
- Checkpoint batching already in place
- Consider batch size tuning in Level 2
- Add metrics/monitoring in Level 3

## Migration Path

### From Level 1 to Level 2:
```java
// Level 1 - no config
context.step("process", String.class, () -> process());

// Level 2 - add config as needed
context.step("process", String.class, () -> process(), 
    StepConfig.builder()
        .retryStrategy(RetryStrategy.exponentialBackoff())
        .build()
);
```

### From Level 2 to Level 3:
```java
// Level 2 - sequential
var r1 = context.step("task1", String.class, () -> task1());
var r2 = context.step("task2", String.class, () -> task2());

// Level 3 - parallel
var results = context.parallel(
    List.of(
        ctx -> ctx.step("task1", String.class, () -> task1()),
        ctx -> ctx.step("task2", String.class, () -> task2())
    ),
    String.class
);
```

## Testing Strategy

### Level 2 Testing:
- Unit tests for retry logic
- Integration tests for callbacks
- Integration tests for invoke
- Configuration validation tests

### Level 3 Testing:
- Concurrency tests (map/parallel)
- Child context isolation tests
- Completion policy tests
- Promise combinator tests
- End-to-end workflow tests

## Documentation Needs

### Level 2:
- Configuration guide
- Retry strategy guide
- Callback patterns
- Error handling guide

### Level 3:
- Concurrency guide
- Map/parallel patterns
- Child context guide
- Promise utilities guide
- Complete API reference

## Conclusion

The Level 1 foundation provides a solid base for Level 2 and Level 3 features. The design is intentionally minimal but extensible, allowing features to be added incrementally without breaking changes.

Key principles:
- **Additive only** - no breaking changes
- **Optional configuration** - sensible defaults
- **Reuse infrastructure** - thread pool, batching, etc.
- **Consistent patterns** - similar APIs across operations
