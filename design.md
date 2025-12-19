# AWS Lambda Durable Execution Java SDK - Design

**Version:** 1.0  
**Date:** December 17, 2025

## Overview

This SDK enables Java developers to build fault-tolerant, long-running workflows with AWS Lambda Durable Functions using a checkpoint-and-replay execution model.

## Architecture

### Core Components

#### 1. **DurableHandler<I, O>**
**Package:** `com.amazonaws.lambda.durable`  
**Responsibility:** Lambda function entry point

- Abstract base class for user Lambda functions
- Delegates execution to `DurableExecution`

#### 2. **DurableExecution**
**Package:** `com.amazonaws.lambda.durable`  
**Responsibility:** Execution lifecycle orchestration

- Loads operation state and creates `ExecutionManager`
- Runs handler in separate thread
- Returns `SUCCESS`/`PENDING`/`FAILED` status

#### 3. **DurableContext**
**Package:** `com.amazonaws.lambda.durable`  
**Responsibility:** User-facing durable operations API

- `step(name, type, func)` / `stepAsync()` - Execute with checkpointing
- `wait(duration)` - Suspend execution
- Delegates to operation classes (`StepOperation`, `WaitOperation`)

#### 4. **ExecutionManager**
**Package:** `com.amazonaws.lambda.durable.execution`  
**Responsibility:** Execution coordination (single entry point)

- **State:** Operations, current checkpoint token, durable execution ARN
- **Thread Coordination:** Register/deregister active threads, suspension logic
- **Phaser Management:** Create and advance phasers for operation coordination
- **Polling:** Background polling for waits and retries
- **Checkpointing:** Delegates to CheckpointManager via callback

#### 5. **CheckpointManager**
**Package:** `com.amazonaws.lambda.durable.execution` (package-private)  
**Responsibility:** Checkpoint batching and API calls

- Queues and batches checkpoint requests (750KB limit)
- Makes AWS API calls via `DurableExecutionClient`
- Notifies `ExecutionManager` via callback (breaks cyclic dependency)

#### 6. **Operation Classes**
**Package:** `com.amazonaws.lambda.durable.operation`  
**Responsibility:** Operation-specific logic

- `StepOperation` - Step execution with retry logic
- `WaitOperation` - Wait checkpointing and polling
- Each implements `DurableOperation<T>` interface with `execute()` and `get()`

### Phaser-Based Thread Coordination

**Java Phaser:** A synchronization barrier that allows threads to wait for each other at specific phases.

**Our Usage:**
- **Phase 0:** Operation is running
- **Phase 1:** Operation complete, waiters reactivate (prevents suspension race)
- **Phase 2+:** Operation fully done

**Example - Preventing Race Condition:**
```java
// stepAsync() starts background thread
StepOperation.execute() {
    executionManager.registerActiveThread("1-step", STEP);
    executor.execute(() -> {
        checkpoint(START);
        result = function.get();
        checkpoint(SUCCEED);
        phaser.arriveAndAwaitAdvance();  // Phase 0->1: notify waiters
        phaser.arriveAndAwaitAdvance();  // Phase 1->2: wait for reactivation
        executionManager.deregisterActiveThread("1-step");
    });
}

// wait() called immediately after
WaitOperation.get() {
    phaser.register();
    executionManager.deregisterActiveThread("Root");  // Allow suspension
    phaser.arriveAndAwaitAdvance();  // Block until step completes
    executionManager.registerActiveThread("Root");    // Reactivate
}

// Result: Step completes and checkpoints before suspension from wait() occurs
```

**Key Insight:** Phase 1 ensures the calling thread reactivates BEFORE the step thread deregisters, preventing premature suspension.

### Supporting Components

#### 6. **SerDes Interface & JacksonSerDes**
**Package:** `com.amazonaws.lambda.durable.serde`  
**Responsibility:** JSON serialization abstraction

- `SerDes` interface for pluggable serialization
- `JacksonSerDes` provides Jackson-based implementation
- Handles user data and operation payload serialization

#### 7. **Model Classes**
**Package:** `com.amazonaws.lambda.durable.model`  
**Responsibility:** API data structures

- `DurableExecutionInput/Output` - Lambda invocation format
- `ErrorObject` - Standardized error representation
- `ExecutionStatus` - Execution state enumeration

#### 8. **Client Package**
**Package:** `com.amazonaws.lambda.durable.client`  
**Responsibility:** AWS API integration

- `DurableExecutionClient` interface for AWS operations
- `LambdaDurableFunctionsClient` AWS SDK implementation
- Handles `CheckpointDurableExecution` and `GetDurableExecutionState` APIs

#### 9. **Exception Classes**
**Package:** `com.amazonaws.lambda.durable.exception`  
**Responsibility:** Control flow and error handling

- `SuspendExecutionException` - Signals execution suspension (for waits)
- Other domain-specific exceptions

## Execution Flow

```
1. Lambda Runtime
   ↓
2. DurableHandler.handleRequest()
   ↓
3. DurableExecution.execute()
   ├── Load all operations (with pagination)
   ├── Create ExecutionManager
   ├── Create DurableContext
   └── Execute user handler
       ↓
4. User Code
   ├── context.step() → CheckpointManager.checkpoint()
   ├── context.wait() → throw SuspendExecutionException
   └── Replay detection (skip completed operations)
       ↓
5. Return Result
   ├── SUCCESS (with result)
   ├── PENDING (suspended)
   └── FAILED (with error)
```

## Key Design Patterns

### Checkpoint-and-Replay
- Operations are assigned sequential IDs
- Completed operations are stored in `ExecutionManager`
- On replay, completed operations return cached results
- New operations execute normally and checkpoint

### Async Checkpointing
- `CheckpointManager` uses `CompletableFuture` for non-blocking API calls
- Operations batch for efficiency (750KB limit)
- Checkpoint tokens ensure consistency

### Type Safety
- Generic types `<I, O>` extracted via reflection
- `Class<T>` parameters for step operations
- Compile-time type checking for user code

### Suspension Model
- `wait()` operations throw `SuspendExecutionException`
- Exception propagates to `DurableExecution`
- Returns `PENDING` status to Lambda runtime
- Lambda service resumes execution after wait period

## Configuration

### Default Behavior
- Uses `LambdaDurableFunctionsClient` with default AWS SDK configuration
- `JacksonSerDes` for JSON serialization
- Single-threaded executor for checkpoint batching

### Customization Points
- Custom `DurableExecutionClient` via constructor
- Custom `SerDes` implementation
- Custom retry policies (future enhancement)

## Error Handling

### User Code Errors
- Caught by `DurableExecution`
- Converted to `ErrorObject`
- Returns `FAILED` status

### API Errors
- Handled by `CheckpointManager`
- Retries with exponential backoff
- Propagates terminal failures

### Replay Safety
- Deterministic operation IDs ensure consistent replay
- Completed operations never re-execute
- State changes are atomic via checkpoint tokens

## Performance Considerations

### Memory Usage
- Operations stored in memory for replay detection
- Large executions may require state pagination
- Completed operations retained for entire execution

### API Efficiency
- Checkpoint batching reduces API calls
- 750KB batch size limit prevents oversized requests
- Async checkpointing prevents blocking user code

### Cold Start
- Minimal initialization overhead
- Lazy loading of AWS clients
- Efficient JSON parsing with Jackson

## Future Enhancements

### Planned Features
- Callback operations for external system integration
- Chained invoke for Lambda-to-Lambda calls
- Context operations for parallel execution
- Map/parallel higher-level constructs

### Optimization Opportunities
- Streaming large state for memory efficiency
- Checkpoint compression for large payloads
- Operation pruning for completed contexts
