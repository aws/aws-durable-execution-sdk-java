# DurableContext Lifecycle Design

**Date:** December 6, 2025  
**Status:** Active Design

## Overview

This document details how `DurableContext` is created, managed, and destroyed throughout a durable execution's lifecycle.

## 1. Input Format (Durable Execution Envelope)

### Lambda Invocation Input

When Lambda invokes a durable function, it provides this envelope:

```json
{
  "durableExecutionArn": "arn:aws:lambda:us-east-1:123456789012:durable-execution:my-execution",
  "checkpointToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "initialExecutionState": {
    "operations": [
      {
        "id": "0",
        "type": "EXECUTION",
        "status": "STARTED",
        "executionDetails": {
          "inputPayload": "{\"orderId\":\"12345\",\"amount\":100.00}"
        }
      },
      {
        "id": "1",
        "type": "STEP",
        "name": "process-payment",
        "status": "SUCCEEDED",
        "stepDetails": {
          "result": "{\"transactionId\":\"txn-789\"}",
          "attempt": 0
        }
      }
    ],
    "nextMarker": "marker-for-pagination"
  }
}
```

### Envelope Structure

```java
public class DurableExecutionInput {
    private String durableExecutionArn;
    private String checkpointToken;
    private ExecutionState initialExecutionState;
    
    public static class ExecutionState {
        private List<Operation> operations;
        private String nextMarker;  // For pagination
    }
}
```

### Operation Structure

```java
public class Operation {
    private String id;              // "0", "1", "2", etc.
    private String parentId;        // For nested contexts
    private String name;            // User-provided name
    private OperationType type;     // EXECUTION, STEP, WAIT, CONTEXT, etc.
    private OperationStatus status; // STARTED, SUCCEEDED, FAILED, PENDING, READY
    
    // Type-specific details
    private ExecutionDetails executionDetails;
    private StepDetails stepDetails;
    private WaitDetails waitDetails;
    private ErrorObject error;
}
```

## 2. Context Creation Flow

### Step 1: Parse Envelope

```java
@Override
public final void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) 
        throws IOException {
    
    // Parse the durable execution envelope
    ObjectMapper mapper = createObjectMapper();
    DurableExecutionInput envelope = mapper.readValue(inputStream, DurableExecutionInput.class);
    
    // Extract components
    String durableExecutionArn = envelope.getDurableExecutionArn();
    String checkpointToken = envelope.getCheckpointToken();
    List<Operation> operations = envelope.getInitialExecutionState().getOperations();
    String nextMarker = envelope.getInitialExecutionState().getNextMarker();
}
```

### Step 2: Create ExecutionState

```java
// ExecutionState manages checkpoint log and communication with service
ExecutionState executionState = new ExecutionState(
    lambdaClient,           // For checkpoint API calls
    executor,               // For background tasks
    serDes,                 // For serialization
    checkpointToken,        // Current checkpoint token
    durableExecutionArn     // Execution identifier
);

// Load initial operations into checkpoint log
executionState.updateOperations(operations);
```

### Step 3: Paginate Additional State (if needed)

```java
// If there are more operations than fit in initial payload
while (nextMarker != null && !nextMarker.isEmpty()) {
    GetDurableExecutionStateResponse response = lambdaClient.getDurableExecutionState(
        GetDurableExecutionStateRequest.builder()
            .checkpointToken(checkpointToken)
            .marker(nextMarker)
            .build()
    );
    
    executionState.updateOperations(response.operations());
    nextMarker = response.nextMarker();
}
```

### Step 4: Create DurableContext

```java
// DurableContext wraps Lambda Context and adds durable operations
DurableContext durableContext = new DurableContext(
    context,          // Lambda Context (for getLogger(), etc.)
    executionState,   // Checkpoint log and state management
    executor          // For async operations
);
```

### Step 5: Extract User's Input

```java
// Find the EXECUTION operation (always id="0")
Operation executionOp = executionState.getOperation("0");
String userPayload = executionOp.getExecutionDetails().getInputPayload();

// Deserialize to user's input type
I userInput = serDes.deserialize(userPayload, getInputType());
```

## 3. Execution Flow

### Overview

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Parse Envelope                                            │
│    - Extract checkpoint token, ARN, operations               │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. Create DurableContext                                     │
│    - Load checkpoint log                                     │
│    - Initialize ExecutionState                               │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. Execute User Handler (in background thread)               │
│    - Call user's handleRequest(input, durableContext)       │
│    - User calls ctx.step(), ctx.wait(), etc.                │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│ 4. Wait for Completion or Suspension                         │
│    - CompletableFuture.anyOf(userFuture, suspendFuture)     │
└────────────────────┬────────────────────────────────────────┘
                     │
         ┌───────────┴───────────┐
         │                       │
         ▼                       ▼
┌────────────────┐      ┌────────────────┐
│ 5a. Suspended  │      │ 5b. Completed  │
│ Return PENDING │      │ Return SUCCESS │
└────────────────┘      └────────────────┘
```

### Detailed Execution

```java
// Execute user handler in background thread
CompletableFuture<O> userFuture = CompletableFuture.supplyAsync(() -> {
    try {
        return handleRequest(userInput, durableContext);
    } catch (Exception e) {
        // User's handler threw exception
        throw new CompletionException(e);
    }
}, executor);

// Get suspension future from ExecutionState
CompletableFuture<Void> suspendFuture = executionState.getSuspendFuture();

// Wait for either to complete
CompletableFuture.anyOf(userFuture, suspendFuture).join();
```

## 4. Suspension

### When Does Suspension Happen?

Suspension occurs when **no active work** is happening:

1. All steps are waiting for external events (retries, waits, callbacks)
2. No threads are actively executing
3. ExecutionState detects idle state

### Suspension Mechanism

```java
// In ExecutionState
private final Semaphore activeTasks = new Semaphore(0);
private final CompletableFuture<Void> suspendFuture = new CompletableFuture<>();

public void startTask() {
    activeTasks.release(); // Increment active count
}

public void completeTask() {
    activeTasks.acquire(); // Decrement active count
    
    if (activeTasks.availablePermits() == 0) {
        // No active tasks, suspend execution
        suspendFuture.complete(null);
        throw new ExecutionSuspendedException();
    }
}
```

### Example: Step with Retry

```java
// User calls step
String result = ctx.step("process", String.class, () -> doWork());

// Step fails, needs retry in 5 seconds
// 1. Step thread completes and deregisters
// 2. ExecutionState sees no active tasks
// 3. Suspension triggered
// 4. Lambda invocation ends
// 5. Service re-invokes after 5 seconds
```

### Suspension Response

```java
if (suspendFuture.isDone()) {
    // Execution suspended
    DurableExecutionOutput output = DurableExecutionOutput.builder()
        .status(InvocationStatus.PENDING)
        .build();
    
    mapper.writeValue(outputStream, output);
    return;
}
```

## 5. Completion

### When Does Completion Happen?

Completion occurs when:
1. User's handler returns successfully
2. User's handler throws an exception (failure)

### Success Completion

```java
if (userFuture.isDone() && !userFuture.isCompletedExceptionally()) {
    O result = userFuture.join();
    
    // Serialize result
    String resultPayload = serDes.serialize(result);
    
    // Checkpoint execution completion
    lambdaClient.checkpointDurableExecution(
        CheckpointDurableExecutionRequest.builder()
            .checkpointToken(checkpointToken)
            .durableExecutionArn(durableExecutionArn)
            .updates(OperationUpdate.builder()
                .id("0")  // Execution operation
                .type(OperationType.EXECUTION)
                .action(OperationAction.SUCCEED)
                .payload(resultPayload)
                .build())
            .build()
    );
    
    // Return success response
    DurableExecutionOutput output = DurableExecutionOutput.builder()
        .status(InvocationStatus.SUCCEEDED)
        .build();
    
    mapper.writeValue(outputStream, output);
}
```

### Failure Completion

```java
if (userFuture.isCompletedExceptionally()) {
    Throwable error = userFuture.exceptionNow();
    
    // Serialize error
    ErrorObject errorObject = ErrorObject.builder()
        .errorType(error.getClass().getSimpleName())
        .errorMessage(error.getMessage())
        .stackTrace(serializeStackTrace(error.getStackTrace()))
        .errorData(serDes.serialize(error))
        .build();
    
    // Checkpoint execution failure
    lambdaClient.checkpointDurableExecution(
        CheckpointDurableExecutionRequest.builder()
            .checkpointToken(checkpointToken)
            .durableExecutionArn(durableExecutionArn)
            .updates(OperationUpdate.builder()
                .id("0")  // Execution operation
                .type(OperationType.EXECUTION)
                .action(OperationAction.FAIL)
                .error(errorObject)
                .build())
            .build()
    );
    
    // Return failure response
    DurableExecutionOutput output = DurableExecutionOutput.builder()
        .status(InvocationStatus.FAILED)
        .build();
    
    mapper.writeValue(outputStream, output);
}
```

## 6. Output Format

### Success Response

```json
{
  "status": "SUCCEEDED"
}
```

### Failure Response

```json
{
  "status": "FAILED"
}
```

### Suspension Response

```json
{
  "status": "PENDING"
}
```

**Note:** The actual result/error is checkpointed separately via the checkpoint API, not in the Lambda response.

## 7. Complete Implementation

### DurableHandler.handleRequest()

```java
@Override
public final void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) 
        throws IOException {
    
    ObjectMapper mapper = createObjectMapper();
    
    try {
        // 1. Parse envelope
        DurableExecutionInput envelope = mapper.readValue(inputStream, DurableExecutionInput.class);
        
        // 2. Create ExecutionState
        ExecutionState executionState = createExecutionState(envelope);
        
        // 3. Create DurableContext
        DurableContext durableContext = new DurableContext(context, executionState, executor);
        
        // 4. Extract user input
        I userInput = extractUserInput(executionState);
        
        // 5. Execute user handler
        CompletableFuture<O> userFuture = CompletableFuture.supplyAsync(
            () -> handleRequest(userInput, durableContext),
            executor
        );
        
        // 6. Wait for completion or suspension
        CompletableFuture<Void> suspendFuture = executionState.getSuspendFuture();
        CompletableFuture.anyOf(userFuture, suspendFuture).join();
        
        // 7. Return appropriate response
        DurableExecutionOutput output;
        
        if (suspendFuture.isDone()) {
            // Suspended
            output = DurableExecutionOutput.builder()
                .status(InvocationStatus.PENDING)
                .build();
                
        } else if (userFuture.isCompletedExceptionally()) {
            // Failed
            checkpointFailure(executionState, userFuture.exceptionNow());
            output = DurableExecutionOutput.builder()
                .status(InvocationStatus.FAILED)
                .build();
                
        } else {
            // Succeeded
            O result = userFuture.join();
            checkpointSuccess(executionState, result);
            output = DurableExecutionOutput.builder()
                .status(InvocationStatus.SUCCEEDED)
                .build();
        }
        
        mapper.writeValue(outputStream, output);
        
    } catch (Exception e) {
        // Unexpected error in SDK itself
        throw new RuntimeException("Durable execution framework error", e);
    }
}
```

## 8. Key Invariants

### Checkpoint Token Management

- ✅ Token is updated after every checkpoint
- ✅ Always use latest token for next checkpoint
- ✅ Token is opaque to SDK (don't parse or modify)

### Operation IDs

- ✅ Sequential: "0", "1", "2", ...
- ✅ Deterministic: Same order on replay
- ✅ Unique within execution

### Suspension Safety

- ✅ Only suspend when no active work
- ✅ All checkpoints persisted before suspension
- ✅ Can resume from any suspension point

### Replay Correctness

- ✅ Operations execute in same order
- ✅ Completed operations return cached results
- ✅ No side effects during replay

## 9. Error Scenarios

### Scenario 1: Checkpoint API Failure

```java
try {
    lambdaClient.checkpointDurableExecution(request);
} catch (Exception e) {
    // Checkpoint failed - what to do?
    // Option A: Retry checkpoint
    // Option B: Fail invocation (service will retry)
    // Option C: Continue without checkpoint (risky)
}
```

**Decision:** Fail invocation, let service retry.

### Scenario 2: Deserialization Failure

```java
try {
    I userInput = serDes.deserialize(payload, inputType);
} catch (SerDesException e) {
    // Can't deserialize user input
    // This is a permanent failure
    checkpointFailure(executionState, e);
    return FAILED;
}
```

### Scenario 3: User Handler Timeout

```java
// Lambda timeout approaching
// ExecutionState should suspend proactively
// But how to detect timeout?

// Option: Monitor remaining time
if (context.getRemainingTimeInMillis() < 5000) {
    // Less than 5 seconds left
    // Trigger suspension
    executionState.suspend();
}
```

## 10. Open Questions

1. **Pagination:** How many operations fit in initial payload? When to paginate?
2. **Timeout handling:** Should SDK proactively suspend before Lambda timeout?
3. **Checkpoint batching:** How to batch checkpoints for performance?
4. **Error recovery:** What errors are retryable vs. permanent?
5. **Metrics:** What should we emit for observability?

## 11. Next Steps

- [ ] Implement envelope parsing
- [ ] Implement ExecutionState creation
- [ ] Implement suspension detection
- [ ] Implement completion handling
- [ ] Add error handling
- [ ] Add timeout detection
- [ ] Add metrics/logging
