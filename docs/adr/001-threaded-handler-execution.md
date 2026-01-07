# ADR-001: Threaded Handler Execution

**Status:** Accepted  
**Date:** 2025-12-29

## Context

Durable functions need to suspend execution immediately at suspension points (wait, callback, retry). The system must return `PENDING` status to Lambda without waiting for the handler to complete.

```java
public String handleRequest(MyInput input, DurableContext context) {
    var result1 = context.step("step1", () -> "first");
    context.wait(Duration.ofHours(1)); // Should suspend HERE
    var result2 = context.step("step2", () -> "second"); // Don't wait for this
    return result1 + result2;
}
```

## Decision

Run the handler in a background thread and race two futures:

```java
var handlerFuture = CompletableFuture.supplyAsync(() -> handler.apply(input, context), executor);
var suspendFuture = executionManager.getSuspendExecutionFuture();

CompletableFuture.anyOf(handlerFuture, suspendFuture).join();

if (suspendFuture.isDone()) {
    return DurableExecutionOutput.pending();
}
```

## Alternatives Considered

### Exception-Based Control Flow
```java
try {
    O result = handler.apply(input, context);
    return DurableExecutionOutput.success(result);
} catch (SuspendExecutionException e) {
    return DurableExecutionOutput.pending();
}
```

**Rejected because:**
1. Users can catch and suppress the exception
2. Requires two-level exception handling (operation wants suspend vs. ExecutionManager confirms suspend)
3. Exceptions for control flow is an anti-pattern

## Consequences

**Positive:**
- Immediate suspension without waiting for handler completion
- Clean separation: suspension decision is in ExecutionManager, not scattered in operations
- Users cannot accidentally suppress suspension

**Negative:**
- More complex threading model
- Requires thread tracking in ExecutionManager
