# AWS Lambda Durable Execution Java SDK - Design

**Version:** 1.0  
**Date:** December 15, 2025

## Overview

This SDK enables Java developers to build fault-tolerant, long-running workflows with AWS Lambda Durable Functions using a checkpoint-and-replay execution model.

## Architecture

### Core Components

#### 1. **DurableHandler<I, O>**
**Package:** `com.amazonaws.lambda.durable`  
**Responsibility:** Lambda function entry point

- Abstract base class for user Lambda functions
- Implements `RequestStreamHandler` for Lambda runtime integration
- Extracts generic types (I, O) from subclass for type safety
- Handles JSON serialization with custom ObjectMapper
- Delegates execution to `DurableExecution`

```java
public abstract class MyFunction extends DurableHandler<MyInput, String> {
    protected String handleRequest(MyInput input, DurableContext context) {
        // User code here
    }
}
```

#### 2. **DurableExecution**
**Package:** `com.amazonaws.lambda.durable`  
**Responsibility:** Execution lifecycle orchestration

- Static entry point for execution logic
- Loads all operation state (with pagination)
- Validates EXECUTION operation and extracts user input
- Creates `ExecutionState` and `CheckpointManager`
- Executes user handler with `DurableContext`
- Returns `SUCCESS`/`PENDING`/`FAILED` status

#### 3. **DurableContext**
**Package:** `com.amazonaws.lambda.durable`  
**Responsibility:** User-facing durable operations API

- `step(name, type, func)` - Execute code with checkpointing
- `wait(duration)` - Suspend execution cost-effectively
- Handles replay detection (skips completed operations)
- Manages operation IDs with `AtomicInteger`
- Delegates checkpointing to `CheckpointManager`

#### 4. **CheckpointManager**
**Package:** `com.amazonaws.lambda.durable.checkpoint`  
**Responsibility:** State persistence coordination

- Batches checkpoint requests for API efficiency
- Manages checkpoint tokens and state updates
- Provides async checkpointing with `CompletableFuture`
- Handles AWS API calls via `DurableExecutionClient`
- Queues operations with size limits (750KB batches)

#### 5. **ExecutionState**
**Package:** `com.amazonaws.lambda.durable.checkpoint`  
**Responsibility:** Current execution state storage

- Stores `DurableExecutionArn` and `CheckpointToken`
- Maintains `Map<String, Operation>` for operation lookup
- Enables O(1) replay detection by operation ID
- Tracks state changes during execution

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
   ├── Create ExecutionState & CheckpointManager
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
- Completed operations are stored in `ExecutionState`
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
