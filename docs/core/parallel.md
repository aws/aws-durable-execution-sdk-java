# Parallel Operations Design Plan

## Overview

Add parallel execution capability to the AWS Lambda Durable Execution SDK, allowing multiple branches to run concurrently within a single durable function execution.

## API Design

### User Interface

```java
try (var parallelContext = ctx.parallel(ParallelConfig.builder().build())) {
    DurableFuture<Boolean> task1 = parallelContext.branch("validate", Boolean.class, branchContext -> validate());
    DurableFuture<String> task2 = parallelContext.branch("process", String.class, branchContext -> process());
    parallelContext.join(); // Wait for completion based on config
    
    // Access results
    Boolean validated = task1.get();
    String processed = task2.get();
}
```

### Core Components

#### 1. ParallelConfig
Configuration object controlling parallel execution behavior:

```java
ParallelConfig config = ParallelConfig.builder()
    .maxConcurrency(5)           // Max branches running simultaneously
    .minSuccessful(3)            // Minimum successful branches required (-1 = all)
    .toleratedFailureCount(2)    // Max failures before stopping execution
    .build();
```

**Configuration Rules:**
- `maxConcurrency`: Controls resource usage, prevents overwhelming the system
- `minSuccessful`: Enables "best effort" scenarios where not all branches need to succeed
- `toleratedFailureCount`: Fail-fast behavior when too many branches fail

#### 2. ParallelContext
Manages the lifecycle of parallel branches:

```java
public class ParallelContext implements AutoCloseable {
    // Create branches
    public <T> DurableFuture<T> branch(String name, Class<T> resultType, Function<DurableContext, T> func);
    public <T> DurableFuture<T> branch(String name, TypeToken<T> resultType, Function<DurableContext, T> func);
    
    // Wait for completion
    public void join();
    
    // AutoCloseable ensures join() is called
    public void close();
}
```

#### 3. DurableContext Integration
Add single method to existing `DurableContext`:

```java
public ParallelContext parallel(ParallelConfig config);
```

## Implementation Strategy

### 1. Leverage Existing Child Context Infrastructure

Each parallel branch will be implemented as a `ChildContextOperation`:
- **Isolation**: Each branch has its own checkpoint log
- **Replay Safety**: Branches replay independently
- **Error Handling**: Branch failures don't affect other branches directly

### 2. Execution Flow

1. **Branch Registration**: `branch()` calls create `ChildContextOperation` instances but don't execute immediately
2. **Execution Start**: `join()` triggers execution of branches respecting `maxConcurrency`
3. **Concurrency Control**: Use a queue to manage pending branches when `maxConcurrency` is reached
4. **Completion Logic**: Monitor success/failure counts against configuration thresholds
5. **Result Collection**: Return results via `DurableFuture` instances


### 4. Error Handling Strategy

**Branch-Level Failures:**
- Individual branch failures are captured in their respective `DurableFuture`
- Don't immediately fail the entire parallel operation
- Count towards `failureCount` for threshold checking

**Parallel-Level Failures:**
- Exceed `toleratedFailureCount`: Stop starting new branches, wait for running ones
- Insufficient `minSuccessful`: Throw `ParallelExecutionException` after all branches complete
- Configuration validation errors: Fail immediately

## Key Design Decisions

### 1. Build on Child Contexts
- **Pros**: Reuses existing isolation and checkpointing logic
- **Cons**: Each branch has overhead of a separate child context
- **Decision**: Acceptable trade-off for clean isolation and replay safety

### 2. Eager vs Lazy Execution
- **Chosen**: Lazy execution (branches start only on `join()`)
- **Rationale**: Allows all branches to be registered before execution starts, enabling better concurrency planning

### 3. AutoCloseable Pattern
- **Purpose**: Ensures `join()` is called even if user forgets
- **Behavior**: If `close()` is called before `join()`, automatically call `join()`

### 4. Configuration Validation
- Validate at `ParallelConfig.build()` time:
  - `maxConcurrency > 0`
  - `minSuccessful >= -1` (where -1 means "all")
  - `toleratedFailureCount >= 0`
  - `minSuccessful + toleratedFailureCount <= total branches` (validated at runtime)

## Implementation Files

### New Files to Create
1. `ParallelConfig.java` - Configuration builder
2. `ParallelContext.java` - User-facing parallel context
3. `operation/ParallelOperation.java` - Core execution logic
4. `exception/ParallelExecutionException.java` - Parallel-specific exceptions

### Files to Modify
1. `DurableContext.java` - Add `parallel()` method
2. `DurableFuture.java` - Ensure compatibility with parallel results (likely no changes needed)

## Testing Strategy

### Unit Tests
- `ParallelConfigTest` - Configuration validation
- `ParallelOperationTest` - Core execution logic with mocked child contexts

### Integration Tests
- Success scenarios with various configurations
- Failure scenarios (exceeding thresholds)
- Concurrency limits
- Replay behavior

### Example Implementation
- `ParallelExample.java` in examples module
- Demonstrate common patterns and error handling
