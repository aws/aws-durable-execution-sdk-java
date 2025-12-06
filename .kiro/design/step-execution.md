# Step Execution Design

**Date:** December 6, 2025  
**Status:** Active Design

## Overview

This document details how `step()` and `stepAsync()` operations work, including replay, checkpointing, retry, and error handling.

## 1. Step API

### Blocking API (Simple)

```java
// Minimal
<T> T step(String name, Class<T> type, Callable<T> action);

// With configuration
<T> T step(String name, Class<T> type, StepConfig config, Callable<T> action);
```

### Async API (Advanced)

```java
// Minimal
<T> DurableFuture<T> stepAsync(String name, Class<T> type, Callable<T> action);

// With configuration
<T> DurableFuture<T> stepAsync(String name, Class<T> type, StepConfig config, Callable<T> action);
```

### Usage Examples

```java
// Blocking (most common)
String result = ctx.step("process", String.class, () -> 
    processData(input)
);

// Async (for composition)
DurableFuture<String> future = ctx.stepAsync("process", String.class, () ->
    processData(input)
);
String result = future.get();

// With retry configuration
String result = ctx.step("process", String.class,
    StepConfig.builder()
        .retryPolicy(RetryPolicy.exponentialBackoff(5))
        .build(),
    () -> processData(input)
);
```

## 2. Step Lifecycle

### State Machine

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Check Checkpoint Log                                      │
│    - Does operation ID exist?                                │
└────────────────┬────────────────────────────────────────────┘
                 │
     ┌───────────┴───────────┐
     │                       │
     ▼                       ▼
┌──────────┐          ┌──────────┐
│ Exists   │          │ New      │
└────┬─────┘          └────┬─────┘
     │                     │
     ▼                     ▼
┌──────────────────┐  ┌──────────────────┐
│ Check Status     │  │ Execute Action   │
└────┬─────────────┘  └────┬─────────────┘
     │                     │
     │                     ▼
     │              ┌──────────────────┐
     │              │ Success?         │
     │              └────┬─────────────┘
     │                   │
     │       ┌───────────┴───────────┐
     │       │                       │
     │       ▼                       ▼
     │  ┌─────────┐            ┌─────────┐
     │  │ Success │            │ Failure │
     │  └────┬────┘            └────┬────┘
     │       │                      │
     │       ▼                      ▼
     │  ┌─────────────┐      ┌──────────────┐
     │  │ Checkpoint  │      │ Retry?       │
     │  │ SUCCEED     │      └────┬─────────┘
     │  └────┬────────┘           │
     │       │          ┌─────────┴─────────┐
     │       │          │                   │
     │       │          ▼                   ▼
     │       │     ┌─────────┐        ┌─────────┐
     │       │     │ Yes     │        │ No      │
     │       │     └────┬────┘        └────┬────┘
     │       │          │                  │
     │       │          ▼                  ▼
     │       │     ┌──────────┐      ┌──────────┐
     │       │     │Checkpoint│      │Checkpoint│
     │       │     │ RETRY    │      │ FAIL     │
     │       │     └────┬─────┘      └────┬─────┘
     │       │          │                  │
     └───────┴──────────┴──────────────────┘
                        │
                        ▼
                 ┌──────────────┐
                 │ Return Result│
                 └──────────────┘
```

## 3. Implementation: Blocking Step

### step() Method

```java
public <T> T step(String name, Class<T> type, Callable<T> action) {
    return step(name, type, StepConfig.defaults(), action);
}

public <T> T step(String name, Class<T> type, StepConfig config, Callable<T> action) {
    // Delegate to async, then block
    DurableFuture<T> future = stepAsync(name, type, config, action);
    return future.get();
}
```

**Key insight:** Blocking step is just async + get()!

## 4. Implementation: Async Step

### stepAsync() Method

```java
public <T> DurableFuture<T> stepAsync(String name, Class<T> type, StepConfig config, Callable<T> action) {
    // 1. Generate operation ID
    String operationId = generateOperationId(); // e.g., "0", "1", "2"
    
    // 2. Check checkpoint log
    Operation existingOp = executionState.getOperation(operationId);
    
    if (existingOp != null) {
        // Replay path
        return handleReplay(operationId, existingOp, type, config);
    } else {
        // First execution path
        return executeStep(operationId, name, type, config, action);
    }
}
```

## 5. Replay Handling

### Check Existing Operation

```java
private <T> DurableFuture<T> handleReplay(String operationId, Operation existingOp, 
                                           Class<T> type, StepConfig config) {
    
    switch (existingOp.getStatus()) {
        case SUCCEEDED:
            // Step completed successfully in previous invocation
            return handleSucceededReplay(operationId, existingOp, type);
            
        case FAILED:
            // Step failed and won't retry
            return handleFailedReplay(operationId, existingOp);
            
        case PENDING:
            // Step is waiting to retry
            return handlePendingReplay(operationId, existingOp, type, config);
            
        case STARTED:
            // Step was interrupted (AT_MOST_ONCE semantics)
            return handleInterruptedReplay(operationId, existingOp, config);
            
        default:
            throw new IllegalStateException("Unexpected status: " + existingOp.getStatus());
    }
}
```

### SUCCEEDED Replay

```java
private <T> DurableFuture<T> handleSucceededReplay(String operationId, Operation existingOp, Class<T> type) {
    // Deserialize cached result
    String resultPayload = existingOp.getStepDetails().getResult();
    T result = serDes.deserialize(resultPayload, type);
    
    // Return completed future
    return DurableFuture.completedFuture(result);
}
```

**Key:** No re-execution, just return cached result!

### FAILED Replay

```java
private <T> DurableFuture<T> handleFailedReplay(String operationId, Operation existingOp) {
    // Deserialize error
    ErrorObject error = existingOp.getStepDetails().getError();
    Throwable throwable = deserializeError(error);
    
    // Return failed future
    return DurableFuture.failedFuture(throwable);
}
```

### PENDING Replay (Waiting to Retry)

```java
private <T> DurableFuture<T> handlePendingReplay(String operationId, Operation existingOp, 
                                                  Class<T> type, StepConfig config) {
    // Step is waiting to retry
    Instant nextAttemptTime = existingOp.getStepDetails().getNextAttemptTimestamp();
    
    if (Instant.now().isBefore(nextAttemptTime)) {
        // Not ready yet, poll for status change
        return pollForReady(operationId, type);
    } else {
        // Ready to retry, execute again
        int attempt = existingOp.getStepDetails().getAttempt();
        return executeStepRetry(operationId, existingOp.getName(), type, config, action, attempt);
    }
}
```

### STARTED Replay (Interrupted)

```java
private <T> DurableFuture<T> handleInterruptedReplay(String operationId, Operation existingOp, 
                                                      StepConfig config) {
    if (config.getSemantics() == StepSemantics.AT_MOST_ONCE) {
        // AT_MOST_ONCE: Don't re-execute, throw error
        throw new StepInterruptedException(
            "Step '" + existingOp.getName() + "' was interrupted and cannot be retried"
        );
    } else {
        // AT_LEAST_ONCE: Re-execute
        int attempt = existingOp.getStepDetails().getAttempt();
        return executeStepRetry(operationId, existingOp.getName(), type, config, action, attempt);
    }
}
```

## 6. First Execution

### Execute Step

```java
private <T> DurableFuture<T> executeStep(String operationId, String name, Class<T> type,
                                          StepConfig config, Callable<T> action) {
    
    // Create future for result
    CompletableFuture<T> internalFuture = new CompletableFuture<>();
    DurableFuture<T> durableFuture = new DurableFutureImpl<>(operationId, internalFuture);
    
    // Mark task as active (prevents suspension)
    executionState.startTask();
    
    // Execute in background thread
    executor.execute(() -> {
        try {
            // Checkpoint START (if AT_MOST_ONCE)
            if (config.getSemantics() == StepSemantics.AT_MOST_ONCE) {
                checkpointStart(operationId, name);
            }
            
            // Execute user's action
            T result = action.call();
            
            // Checkpoint SUCCESS
            checkpointSuccess(operationId, name, result);
            
            // Complete future
            internalFuture.complete(result);
            
        } catch (Throwable error) {
            // Handle error (retry or fail)
            handleStepError(operationId, name, error, config, 0, internalFuture);
            
        } finally {
            // Mark task as complete
            executionState.completeTask();
        }
    });
    
    return durableFuture;
}
```

## 7. Error Handling and Retry

### Handle Step Error

```java
private <T> void handleStepError(String operationId, String name, Throwable error,
                                  StepConfig config, int attempt, 
                                  CompletableFuture<T> future) {
    
    // Ask retry policy what to do
    RetryDecision decision = config.getRetryPolicy().decide(error, attempt);
    
    if (decision.shouldRetry()) {
        // Schedule retry
        Duration delay = decision.getDelay();
        
        logger.log("Step '" + name + "' failed, retrying in " + delay.toSeconds() + "s");
        
        // Checkpoint RETRY
        checkpointRetry(operationId, name, error, delay, attempt);
        
        // Schedule poll for when retry is ready
        scheduleRetryPoll(operationId, delay, future);
        
    } else {
        // No retry, fail permanently
        logger.log("Step '" + name + "' failed permanently");
        
        // Checkpoint FAIL
        checkpointFail(operationId, name, error);
        
        // Complete future with error
        future.completeExceptionally(error);
    }
}
```

### Checkpoint Operations

```java
private void checkpointStart(String operationId, String name) {
    OperationUpdate update = OperationUpdate.builder()
        .id(operationId)
        .name(name)
        .type(OperationType.STEP)
        .action(OperationAction.START)
        .build();
    
    executionState.checkpoint(update).join(); // Synchronous!
}

private void checkpointSuccess(String operationId, String name, Object result) {
    String payload = serDes.serialize(result);
    
    OperationUpdate update = OperationUpdate.builder()
        .id(operationId)
        .name(name)
        .type(OperationType.STEP)
        .action(OperationAction.SUCCEED)
        .payload(payload)
        .build();
    
    executionState.checkpoint(update).join();
}

private void checkpointRetry(String operationId, String name, Throwable error, 
                              Duration delay, int attempt) {
    ErrorObject errorObj = serializeError(error);
    
    OperationUpdate update = OperationUpdate.builder()
        .id(operationId)
        .name(name)
        .type(OperationType.STEP)
        .action(OperationAction.RETRY)
        .error(errorObj)
        .stepOptions(StepOptions.builder()
            .nextAttemptDelaySeconds((int) delay.toSeconds())
            .build())
        .build();
    
    executionState.checkpoint(update).join();
}

private void checkpointFail(String operationId, String name, Throwable error) {
    ErrorObject errorObj = serializeError(error);
    
    OperationUpdate update = OperationUpdate.builder()
        .id(operationId)
        .name(name)
        .type(OperationType.STEP)
        .action(OperationAction.FAIL)
        .error(errorObj)
        .build();
    
    executionState.checkpoint(update).join();
}
```

## 8. Step Semantics

### AT_LEAST_ONCE (Default)

```java
StepSemantics.AT_LEAST_ONCE
```

**Behavior:**
- Step may execute multiple times
- START checkpoint is in-memory only (not persisted)
- If interrupted, re-executes on replay
- **Use for:** Idempotent operations

**Example:**
```java
// This is safe to run multiple times
String result = ctx.step("uppercase", String.class, () ->
    input.toUpperCase()
);
```

### AT_MOST_ONCE

```java
StepSemantics.AT_MOST_ONCE
```

**Behavior:**
- Step executes at most once per attempt
- START checkpoint is persisted (synchronous)
- If interrupted, throws StepInterruptedException
- **Use for:** Non-idempotent operations

**Example:**
```java
// This should only run once (e.g., charging credit card)
String txnId = ctx.step("charge", String.class,
    StepConfig.builder()
        .semantics(StepSemantics.AT_MOST_ONCE)
        .build(),
    () -> chargeCard(amount)
);
```

## 9. Retry Policies

### Exponential Backoff (Default)

```java
RetryPolicy.exponentialBackoff(maxAttempts)
```

**Behavior:**
- Delay: 1s, 2s, 4s, 8s, 16s, ...
- Max delay: 60s
- Retries all exceptions

**Example:**
```java
String result = ctx.step("api-call", String.class,
    StepConfig.builder()
        .retryPolicy(RetryPolicy.exponentialBackoff(5))
        .build(),
    () -> callExternalApi()
);
```

### Fixed Delay

```java
RetryPolicy.fixedDelay(maxAttempts, delay)
```

**Behavior:**
- Same delay between attempts
- Retries all exceptions

### Custom Policy

```java
RetryPolicy.custom((error, attempt) -> {
    if (error instanceof TimeoutException) {
        return RetryDecision.retry(Duration.ofSeconds(5));
    } else if (error instanceof RateLimitException) {
        return RetryDecision.retry(Duration.ofMinutes(1));
    } else {
        return RetryDecision.fail();
    }
})
```

### No Retry

```java
RetryPolicy.noRetry()
```

**Behavior:**
- Fail immediately on error
- No retries

## 10. Operation ID Generation

### Sequential IDs

```java
private int operationCounter = 0;

private String generateOperationId() {
    return String.valueOf(operationCounter++);
}
```

**IDs:** "0", "1", "2", "3", ...

**Key:** Must be deterministic! Same order on replay.

### Nested Context IDs

```java
// Root context: "0", "1", "2"
// Child context (operation 3): "3-0", "3-1", "3-2"
// Grandchild (operation 3-1): "3-1-0", "3-1-1"

private String generateOperationId() {
    String id = String.valueOf(operationCounter++);
    if (parentContextId != null) {
        return parentContextId + "-" + id;
    }
    return id;
}
```

## 11. Polling for Retry

### Schedule Poll

```java
private void scheduleRetryPoll(String operationId, Duration delay, CompletableFuture<T> future) {
    Instant nextAttemptTime = Instant.now().plus(delay);
    
    executor.schedule(() -> {
        pollForReady(operationId, future);
    }, delay.toMillis(), TimeUnit.MILLISECONDS);
}

private void pollForReady(String operationId, CompletableFuture<T> future) {
    // Check if operation is now READY
    Operation op = executionState.getOperation(operationId);
    
    if (op.getStatus() == OperationStatus.READY) {
        // Ready to retry, execute again
        executeStepRetry(operationId, op.getName(), type, config, action, op.getAttempt());
    } else {
        // Not ready yet, poll again
        executor.schedule(() -> {
            pollForReady(operationId, future);
        }, 1000, TimeUnit.MILLISECONDS);
    }
}
```

**Note:** Polling happens in background. If no other work, execution suspends.

## 12. Complete Flow Example

### User Code

```java
String result = ctx.step("process", String.class, () -> 
    processData(input)
);
```

### First Invocation

```
1. generateOperationId() → "0"
2. getOperation("0") → null (not in checkpoint log)
3. executeStep():
   - Start background thread
   - Execute action.call()
   - Action succeeds
   - checkpointSuccess("0", result)
   - Complete future
4. Return result to user
```

### Second Invocation (Replay)

```
1. generateOperationId() → "0" (same!)
2. getOperation("0") → Operation{status=SUCCEEDED, result="..."}
3. handleSucceededReplay():
   - Deserialize cached result
   - Return completed future
4. Return result to user (no re-execution!)
```

### With Retry

**First Invocation:**
```
1. generateOperationId() → "0"
2. getOperation("0") → null
3. executeStep():
   - Execute action.call()
   - Action throws exception
   - RetryPolicy decides: retry in 5s
   - checkpointRetry("0", error, 5s)
   - Schedule poll for 5s later
   - Execution suspends (no active work)
```

**Second Invocation (5s later):**
```
1. generateOperationId() → "0"
2. getOperation("0") → Operation{status=PENDING, nextAttempt=now}
3. handlePendingReplay():
   - Ready to retry
   - executeStepRetry() with attempt=1
   - Execute action.call()
   - Action succeeds
   - checkpointSuccess("0", result)
4. Return result to user
```

## 13. Key Invariants

### Determinism

✅ **Operation IDs must be deterministic**
- Same order on every replay
- Sequential: "0", "1", "2", ...

✅ **Checkpoint log is source of truth**
- If operation exists, use cached result
- Never re-execute completed operations

### Idempotency

✅ **Checkpoints are idempotent**
- Can checkpoint same operation multiple times
- Service handles deduplication

✅ **AT_LEAST_ONCE steps may execute multiple times**
- User code must be idempotent
- Or use AT_MOST_ONCE semantics

### Suspension

✅ **Execution suspends when no active work**
- After checkpointing retry
- After checkpointing wait
- When all steps are blocked

## 14. Error Scenarios

### Scenario 1: Checkpoint Fails

```java
try {
    executionState.checkpoint(update).join();
} catch (Exception e) {
    // Checkpoint failed - what to do?
    // Option: Fail invocation, let service retry
    throw new CheckpointException("Failed to checkpoint step", e);
}
```

### Scenario 2: Deserialization Fails

```java
try {
    T result = serDes.deserialize(payload, type);
} catch (SerDesException e) {
    // Can't deserialize cached result
    // This is a permanent failure
    throw new StepException("Failed to deserialize step result", e);
}
```

### Scenario 3: Action Throws Non-Retryable Error

```java
RetryPolicy.custom((error, attempt) -> {
    if (error instanceof ValidationException) {
        // Don't retry validation errors
        return RetryDecision.fail();
    }
    return RetryDecision.retry(Duration.ofSeconds(5));
})
```

## 15. Performance Considerations

### Checkpoint Batching

```java
// Don't checkpoint every step immediately
// Batch multiple checkpoints together
executionState.checkpoint(update); // Async, batched

// Only synchronous for AT_MOST_ONCE START
executionState.checkpoint(update).join(); // Synchronous
```

### Thread Pool Sizing

```java
// Fixed thread pool for Java 17
Executor executor = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors() * 2
);

// Virtual threads for Java 21+
Executor executor = Executors.newVirtualThreadPerTaskExecutor();
```

## 16. Testing

### Mock ExecutionState

```java
@Test
void testStepReplay() {
    ExecutionState mockState = mock(ExecutionState.class);
    
    // Setup: Operation already succeeded
    Operation existingOp = Operation.builder()
        .id("0")
        .status(OperationStatus.SUCCEEDED)
        .stepDetails(StepDetails.builder()
            .result("\"cached-result\"")
            .build())
        .build();
    
    when(mockState.getOperation("0")).thenReturn(existingOp);
    
    DurableContext ctx = new DurableContext(lambdaContext, mockState, executor);
    
    // Execute step
    String result = ctx.step("test", String.class, () -> {
        fail("Should not execute - should use cached result");
        return null;
    });
    
    // Verify
    assertEquals("cached-result", result);
    verify(mockState, never()).checkpoint(any());
}
```

## 17. Open Questions

1. **Checkpoint batching window:** How long to wait before flushing?
2. **Max retry delay:** Should we cap exponential backoff?
3. **Retry jitter:** Add randomness to avoid thundering herd?
4. **Timeout per attempt:** Should steps have per-attempt timeout?
5. **Metrics:** What should we emit for observability?

## 18. Next Steps

- [ ] Implement step execution logic
- [ ] Implement retry policies
- [ ] Implement checkpoint batching
- [ ] Add error handling
- [ ] Add metrics/logging
- [ ] Write tests
