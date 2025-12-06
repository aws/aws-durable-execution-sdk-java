# ExecutionState Design

**Date:** December 6, 2025  
**Status:** Active Design - MVP

## Overview

This document details the ExecutionState class - the core state management component that handles checkpoint log, API communication, and suspension detection.

## 1. ExecutionState Responsibilities

### Core Functions

1. **Checkpoint Log** - Store operation history
2. **API Communication** - Call checkpoint API
3. **Suspension Detection** - Know when to suspend
4. **Operation Tracking** - Track active operations
5. **Checkpoint Batching** - Batch updates for performance

## 2. Class Structure

### ExecutionState Class

```java
package com.amazonaws.lambda.durable.internal;

import java.util.Map;
import java.util.concurrent.*;
import software.amazon.awssdk.services.lambda.LambdaClient;

public class ExecutionState {
    
    // Dependencies
    private final LambdaClient lambdaClient;
    private final Executor executor;
    private final SerDes serDes;
    
    // Execution metadata
    private String checkpointToken;
    private final String durableExecutionArn;
    
    // Checkpoint log (operation history)
    private final Map<String, Operation> operations = new ConcurrentHashMap<>();
    
    // Active task tracking
    private final Semaphore activeTasks = new Semaphore(0);
    
    // Checkpoint batching
    private final BlockingQueue<OperationUpdate> pendingUpdates = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService batcher;
    
    // Suspension signal
    private final CompletableFuture<Void> suspendFuture = new CompletableFuture<>();
    
    public ExecutionState(LambdaClient lambdaClient, Executor executor, SerDes serDes,
                         String checkpointToken, String durableExecutionArn) {
        this.lambdaClient = lambdaClient;
        this.executor = executor;
        this.serDes = serDes;
        this.checkpointToken = checkpointToken;
        this.durableExecutionArn = durableExecutionArn;
        
        // Start background checkpoint batcher
        this.batcher = Executors.newSingleThreadScheduledExecutor();
        this.batcher.scheduleAtFixedRate(
            this::flushCheckpoints,
            100, 100, TimeUnit.MILLISECONDS
        );
    }
}
```

## 3. Checkpoint Log

### Store Operations

```java
/**
 * Update checkpoint log with new operations.
 * Called when loading initial state or receiving checkpoint responses.
 */
public void updateOperations(List<Operation> newOperations) {
    for (Operation op : newOperations) {
        operations.put(op.id(), op);
    }
}

/**
 * Get operation from checkpoint log.
 * Returns null if operation doesn't exist.
 */
public Operation getOperation(String operationId) {
    return operations.get(operationId);
}
```

### Usage

```java
// During replay
Operation existingOp = executionState.getOperation("0");
if (existingOp != null && existingOp.status() == OperationStatus.SUCCEEDED) {
    // Use cached result
    return deserialize(existingOp.stepDetails().result());
}
```

## 4. Checkpoint API

### Send Checkpoint

```java
/**
 * Queue an operation update for checkpointing.
 * Updates are batched and sent asynchronously.
 */
public void checkpoint(OperationUpdate update) {
    pendingUpdates.offer(update);
}

/**
 * Send checkpoint synchronously (for critical operations).
 * Used for AT_MOST_ONCE step START.
 */
public CompletableFuture<Void> checkpointSync(OperationUpdate update) {
    pendingUpdates.offer(update);
    
    CompletableFuture<Void> future = new CompletableFuture<>();
    
    // Trigger immediate flush
    executor.execute(() -> {
        flushCheckpoints();
        future.complete(null);
    });
    
    return future;
}
```

### Flush Checkpoints (Batching)

```java
/**
 * Flush pending checkpoints to backend.
 * Called periodically by background thread.
 */
private void flushCheckpoints() {
    // Collect pending updates
    List<OperationUpdate> batch = new ArrayList<>();
    pendingUpdates.drainTo(batch, 100); // Max 100 per batch
    
    if (batch.isEmpty()) {
        return;
    }
    
    try {
        // Call checkpoint API
        CheckpointDurableExecutionResponse response = lambdaClient.checkpointDurableExecution(
            CheckpointDurableExecutionRequest.builder()
                .checkpointToken(checkpointToken)
                .durableExecutionArn(durableExecutionArn)
                .updates(batch)
                .build()
        );
        
        // Update checkpoint token
        checkpointToken = response.checkpointToken();
        
        // Update checkpoint log with response
        updateOperations(response.newExecutionState().operations());
        
    } catch (Exception e) {
        // Log error, will retry on next batch
        logger.error("Checkpoint failed", e);
        
        // Re-queue failed updates
        pendingUpdates.addAll(batch);
    }
}
```

## 5. Active Task Tracking

### Track Active Work

```java
/**
 * Mark a task as active (prevents suspension).
 */
public void startTask() {
    activeTasks.release(); // Increment count
}

/**
 * Mark a task as complete (may trigger suspension).
 */
public void completeTask() {
    try {
        activeTasks.acquire(); // Decrement count
        
        // Check if we should suspend
        if (activeTasks.availablePermits() == 0) {
            // No active tasks, suspend execution
            suspendExecution();
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

### Suspension Detection

```java
/**
 * Suspend execution when no active work.
 */
private void suspendExecution() {
    // Flush any pending checkpoints first
    flushCheckpoints();
    
    // Signal suspension
    suspendFuture.complete(null);
    
    // Throw exception to unwind stack
    throw new ExecutionSuspendedException();
}

/**
 * Get future that completes when execution should suspend.
 */
public CompletableFuture<Void> getSuspendFuture() {
    return suspendFuture;
}
```

## 6. Usage in Operations

### Step Execution

```java
// In step execution
public <T> T step(String name, Class<T> type, Callable<T> action) {
    String operationId = generateOperationId();
    
    // Mark task as active
    executionState.startTask();
    
    executor.execute(() -> {
        try {
            // Execute action
            T result = action.call();
            
            // Checkpoint success
            executionState.checkpoint(OperationUpdate.builder()
                .id(operationId)
                .type(OperationType.STEP)
                .action(OperationAction.SUCCEED)
                .payload(serDes.serialize(result))
                .build());
                
        } finally {
            // Mark task as complete (may suspend)
            executionState.completeTask();
        }
    });
}
```

### Wait Execution

```java
// In wait execution
public void wait(Duration duration) {
    String operationId = generateOperationId();
    
    // Checkpoint wait (no active task needed)
    executionState.checkpoint(OperationUpdate.builder()
        .id(operationId)
        .type(OperationType.WAIT)
        .action(OperationAction.START)
        .waitOptions(WaitOptions.builder()
            .waitSeconds((int) duration.toSeconds())
            .build())
        .build());
    
    // Wait doesn't create active task, so execution suspends immediately
}
```

## 7. Checkpoint Batching

### Why Batch?

**Without batching:**
- 1 API call per operation
- High latency (100ms per call)
- Poor throughput (10 ops/sec)

**With batching:**
- 1 API call per batch (up to 100 operations)
- Same latency (100ms per batch)
- High throughput (1000 ops/sec)

### Batching Strategy

```java
// Background thread flushes every 100ms
batcher.scheduleAtFixedRate(
    this::flushCheckpoints,
    100, 100, TimeUnit.MILLISECONDS
);

// Collect up to 100 updates per batch
pendingUpdates.drainTo(batch, 100);
```

### Trade-offs

| Batch Window | Throughput | Latency |
|--------------|------------|---------|
| 10ms | High | Low |
| 100ms | High | Medium |
| 1000ms | High | High |

**MVP:** 100ms window (good balance)

## 8. Checkpoint Token Management

### Token Flow

```java
// Initial token from input
String checkpointToken = input.getCheckpointToken();

// After each checkpoint
CheckpointDurableExecutionResponse response = lambdaClient.checkpointDurableExecution(...);
checkpointToken = response.checkpointToken(); // Update token

// Use updated token for next checkpoint
CheckpointDurableExecutionRequest.builder()
    .checkpointToken(checkpointToken) // Always use latest
    .build();
```

### Token Rules

✅ **Always use latest token**  
✅ **Token is opaque** - Don't parse or modify  
✅ **Token per execution** - Different executions have different tokens  
✅ **Token changes** - Every checkpoint returns new token  

## 9. Error Handling

### Checkpoint Failure

```java
try {
    lambdaClient.checkpointDurableExecution(request);
} catch (Exception e) {
    // Log error
    logger.error("Checkpoint failed", e);
    
    // Re-queue updates for retry
    pendingUpdates.addAll(batch);
    
    // Don't throw - will retry on next batch
}
```

### Retry Strategy

- **Transient errors:** Retry automatically (next batch)
- **Permanent errors:** Log and continue (may lose checkpoints)
- **Token errors:** Fatal (execution can't continue)

## 10. Suspension Scenarios

### Scenario 1: Step with Retry

```java
String result = ctx.step("api", String.class, () -> callApi());
```

**Flow:**
1. startTask() → activeTasks = 1
2. Execute action → fails
3. Checkpoint RETRY
4. completeTask() → activeTasks = 0
5. Suspend (no active work)

### Scenario 2: Multiple Steps

```java
String r1 = ctx.step("step1", String.class, () -> task1());
String r2 = ctx.step("step2", String.class, () -> task2());
```

**Flow:**
1. Step 1: startTask() → activeTasks = 1
2. Step 1: Execute, checkpoint, completeTask() → activeTasks = 0
3. Step 2: startTask() → activeTasks = 1
4. Step 2: Execute, checkpoint, completeTask() → activeTasks = 0
5. No more work, suspend

### Scenario 3: Wait

```java
ctx.wait(Duration.ofSeconds(5));
```

**Flow:**
1. Checkpoint WAIT
2. No startTask() called
3. activeTasks = 0
4. Suspend immediately

## 11. Cleanup

### Shutdown

```java
/**
 * Shutdown ExecutionState (called at end of invocation).
 */
public void shutdown() {
    // Flush any remaining checkpoints
    flushCheckpoints();
    
    // Shutdown batcher
    batcher.shutdown();
    try {
        batcher.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        batcher.shutdownNow();
    }
}
```

## 12. Testing

### Test Checkpoint Batching

```java
@Test
void testCheckpointBatching() {
    ExecutionState state = new ExecutionState(...);
    
    // Queue multiple updates
    for (int i = 0; i < 10; i++) {
        state.checkpoint(OperationUpdate.builder()
            .id(String.valueOf(i))
            .type(OperationType.STEP)
            .action(OperationAction.SUCCEED)
            .build());
    }
    
    // Wait for batch
    Thread.sleep(150);
    
    // Verify single API call with all updates
    verify(lambdaClient, times(1)).checkpointDurableExecution(
        argThat(req -> req.updates().size() == 10)
    );
}
```

### Test Suspension Detection

```java
@Test
void testSuspensionDetection() {
    ExecutionState state = new ExecutionState(...);
    
    // Start task
    state.startTask();
    assertEquals(1, state.getActiveTasks());
    
    // Complete task
    assertThrows(ExecutionSuspendedException.class, () -> {
        state.completeTask();
    });
    
    // Verify suspended
    assertTrue(state.getSuspendFuture().isDone());
}
```

## 13. Performance Considerations

### Memory Usage

```java
// Checkpoint log size
Map<String, Operation> operations; // ~1KB per operation

// For 1000 operations: ~1MB
// For 10000 operations: ~10MB
```

**Recommendation:** Limit execution to < 10000 operations

### CPU Usage

```java
// Checkpoint batching: 10 batches/sec
// Each batch: ~10ms CPU
// Total: ~100ms CPU/sec (negligible)
```

### Network Usage

```java
// Checkpoint API call: ~100ms latency
// Payload size: ~10KB per batch
// Throughput: ~100KB/sec
```

## 14. Configuration

### Tunable Parameters

```java
public class ExecutionStateConfig {
    // Batch window (default: 100ms)
    private Duration batchWindow = Duration.ofMillis(100);
    
    // Max batch size (default: 100)
    private int maxBatchSize = 100;
    
    // Checkpoint retry attempts (default: 3)
    private int checkpointRetries = 3;
}
```

### MVP Defaults

- **Batch window:** 100ms
- **Max batch size:** 100 operations
- **Checkpoint retries:** 3 attempts

## 15. Thread Safety

### Concurrent Access

```java
// Thread-safe collections
private final Map<String, Operation> operations = new ConcurrentHashMap<>();
private final BlockingQueue<OperationUpdate> pendingUpdates = new LinkedBlockingQueue<>();

// Atomic operations
private final Semaphore activeTasks = new Semaphore(0);
```

### Synchronization

```java
// No explicit locks needed
// ConcurrentHashMap handles concurrent reads/writes
// BlockingQueue handles concurrent offer/drain
// Semaphore handles concurrent acquire/release
```

## 16. MVP Scope

For MVP, we need:

✅ **Checkpoint log** - Store operations  
✅ **Checkpoint API** - Send updates  
✅ **Active task tracking** - Semaphore-based  
✅ **Suspension detection** - When activeTasks = 0  
✅ **Checkpoint batching** - 100ms window  
✅ **Token management** - Update after each checkpoint  

Not needed for MVP:
❌ Advanced batching strategies  
❌ Checkpoint compression  
❌ Checkpoint caching  
❌ Metrics/observability  

## 17. Open Questions

1. **Max operations:** What's the limit per execution?
2. **Checkpoint size:** What's the max payload size?
3. **Token expiration:** Do tokens expire?
4. **Concurrent checkpoints:** Can we send multiple batches in parallel?

## 18. Integration Example

### Complete Flow

```java
// 1. Create ExecutionState
ExecutionState state = new ExecutionState(
    lambdaClient, executor, serDes,
    input.getCheckpointToken(),
    input.getDurableExecutionArn()
);

// 2. Load initial operations
state.updateOperations(input.getInitialExecutionState().getOperations());

// 3. Create DurableContext
DurableContext ctx = new DurableContext(lambdaContext, state, executor);

// 4. Execute user handler
CompletableFuture<String> userFuture = CompletableFuture.supplyAsync(
    () -> handler.handleRequest(event, ctx),
    executor
);

// 5. Wait for completion or suspension
CompletableFuture.anyOf(userFuture, state.getSuspendFuture()).join();

// 6. Cleanup
state.shutdown();

// 7. Return result
if (state.getSuspendFuture().isDone()) {
    return PENDING;
} else {
    return SUCCEEDED;
}
```

## 19. Next Steps

- [ ] Implement ExecutionState class
- [ ] Implement checkpoint batching
- [ ] Implement active task tracking
- [ ] Implement suspension detection
- [ ] Add checkpoint error handling
- [ ] Write ExecutionState tests
- [ ] Add metrics/logging
