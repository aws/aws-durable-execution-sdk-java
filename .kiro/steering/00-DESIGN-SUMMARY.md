# AWS Lambda Durable Execution Java SDK - Design Summary

**Version:** 2.0  
**Date:** December 6, 2025  
**Status:** Active Design - Ready for PoC Implementation

> **This is the single source of truth for the new Java SDK design.**  
> This is a fresh implementation, not a refactor of existing code.  
> For detailed documentation, see files in `design/` directory.

## 1. What We're Building

A **simple, idiomatic Java SDK** for AWS Lambda Durable Executions that enables:
- Long-running workflows (up to 1 year)
- Automatic state management and replay
- Transparent checkpointing
- Built-in retry and error handling
- Familiar Java patterns (Future, Callable, Builder)

**Target:** Java 17 LTS, ~500 lines of core code

## 2. Core Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **API Style** | Context object | Explicit, testable, aligns with TS/Python SDKs |
| **Async Model** | Blocking + DurableFuture | Simple default, async when needed |
| **Handler Registration** | Base class OR wrapper function | Base class recommended, wrapper for flexibility |
| **Serialization** | Jackson (pluggable SerDes) | Standard, familiar, extensible |
| **Concurrency** | CompletableFuture + Semaphore | Idiomatic Java 17 patterns |
| **Checkpointing** | Batched (100ms window) | Reduces API calls 10-100x |
| **Java Version** | Java 17 LTS | Lambda support, no virtual threads needed |

## 3. Core API

### DurableContext Interface

```java
public interface DurableContext {
    // Step execution
    <T> T step(String name, Class<T> type, Callable<T> action);
    <T> DurableFuture<T> stepAsync(String name, Class<T> type, Callable<T> action);
    
    // Wait operation
    void wait(Duration duration);
    DurableFuture<Void> timer(Duration duration);
    
    // Parallel execution (post-MVP)
    <T> List<T> parallel(List<Callable<T>> operations, Class<T> type);
    
    // Callbacks (post-MVP)
    <T> Callback<T> createCallback(String name, Class<T> type);
    
    // Lambda invocation (post-MVP)
    <T> T invoke(InvokeOptions<T> options);
}
```

### Handler Registration (Two Options)

**Option 1: Base Class (Recommended)**
```java
public class MyHandler extends DurableHandler<MyEvent, String> {
    @Override
    public String handleRequest(MyEvent event, DurableContext ctx) {
        String result = ctx.step("process", String.class, () -> 
            processData(event)
        );
        ctx.wait(Duration.ofHours(24));
        return result;
    }
}
```

**Option 2: Wrapper Function**
```java
public class MyHandler {
    public static final RequestStreamHandler HANDLER = 
        DurableExecution.wrap(MyHandler::handle, MyEvent.class, String.class);
    
    public static String handle(MyEvent event, DurableContext ctx) {
        // Same logic
    }
}
```

## 4. Architecture Overview

```
User Handler
     ↓
DurableContext (API)
     ↓
DurableContextImpl (orchestration)
     ↓
ExecutionState (checkpointing, batching, suspension)
     ↓
Lambda Durable Execution API
```

### Key Components

1. **DurableHandler<I,O>** - Base class for handlers
2. **DurableContext** - Public API interface
3. **DurableContextImpl** - Implementation with replay logic
4. **ExecutionState** - Checkpoint batching, task tracking, suspension
5. **DurableFuture<T>** - Async operation handle
6. **SerDes** - Pluggable serialization (Jackson default)
7. **RetryPolicy** - Configurable retry with exponential backoff

## 5. How It Works

### Step Execution Flow

```java
// User code
String result = ctx.step("process", String.class, () -> processData());
```

**What happens:**

1. **Check replay** - Is this step already in checkpoint log?
   - If YES → Return cached result (no re-execution)
   - If NO → Continue to execution

2. **Execute** - Run the Callable in thread pool
   - Increment active task counter (Semaphore)
   - Execute with retry policy if configured
   - Serialize result

3. **Checkpoint** - Queue update for batching
   - Add to BlockingQueue (non-blocking)
   - Background thread flushes every 100ms
   - Update checkpoint log on success

4. **Complete** - Return result to user
   - Decrement active task counter
   - Check if execution should suspend (no active tasks)

### Checkpoint Batching

**Why batch?**
- Without: 1 API call per operation = 10 ops/sec
- With batching: 100 operations per call = 1000 ops/sec

**How it works:**
```java
// Background thread runs every 100ms
BlockingQueue<OperationUpdate> pendingUpdates;
ScheduledExecutorService batcher;

batcher.scheduleAtFixedRate(() -> {
    List<OperationUpdate> batch = new ArrayList<>();
    pendingUpdates.drainTo(batch, 100); // Max 100 per batch
    
    if (!batch.isEmpty()) {
        lambdaClient.checkpointDurableExecution(batch);
    }
}, 100, 100, TimeUnit.MILLISECONDS);
```

### Suspension Detection

**When to suspend:**
- When `activeTasks` semaphore reaches 0
- Means: No steps executing, no timers pending
- SDK calls suspend API, Lambda pauses execution

**Reactivation:**
- Service resumes execution when timer completes or callback received
- Lambda invokes handler again with updated checkpoint log
- Replay skips completed operations, continues from suspension point

## 6. MVP Scope

### Included in PoC

✅ **Core Operations**
- `step()` - Blocking step execution
- `stepAsync()` - Async step execution  
- `wait()` - Blocking wait
- `timer()` - Async wait

✅ **Core Features**
- Handler registration (base class + wrapper)
- Replay from checkpoint log
- Checkpoint batching (100ms window)
- Exponential backoff retry
- Suspension detection
- Jackson serialization

✅ **Error Handling**
- Retry with exponential backoff
- Configurable retry policies
- Exception propagation

### Post-MVP

❌ `parallel()` - Parallel execution
❌ `createCallback()` - Callback operations
❌ `invoke()` - Lambda invocation
❌ Advanced retry strategies
❌ Custom SerDes implementations
❌ Metrics/observability

## 7. Implementation Approach

### Phase 1: Core Classes (Week 1)
1. DurableContext interface
2. DurableHandler base class
3. DurableExecution wrapper
4. SerDes interface + Jackson implementation

### Phase 2: Execution Logic (Week 2)
1. DurableContextImpl with replay
2. ExecutionState with batching
3. DurableFuture implementation
4. RetryPolicy implementation

### Phase 3: Testing & Polish (Week 3)
1. Unit tests
2. Integration tests
3. Example handlers
4. Documentation

**Target:** ~500 lines of core code, fully functional PoC

## 8. Why This Design?

### Compared to TypeScript/Python SDKs

✅ **Consistent API** - Same concepts (step, wait, context)
✅ **Language idioms** - Uses Java patterns (Future, Callable, Builder)
✅ **Same batching** - 100ms window (more aggressive than Python's 1s)

### Compared to Temporal/Restate

✅ **Simpler** - No workflow engine, no custom DSL
✅ **Native Lambda** - Built for Lambda Durable Executions
✅ **Lightweight** - ~500 LOC vs 1850+ LOC

### Key Innovations

1. **Checkpoint batching** - Reduces API calls 10-100x
2. **Semaphore-based suspension** - Simple, reliable detection
3. **Dual handler patterns** - Base class OR wrapper function
4. **Replay-safe futures** - DurableFuture with checkpoint awareness

## 9. Design Documents

### Core Design (Read First)
1. **00-DESIGN-SUMMARY.md** ← You are here
2. **design/handler-registration.md** - Base class vs wrapper patterns
3. **design/context-lifecycle.md** - Envelope parsing, context creation
4. **design/step-execution.md** - Replay, checkpoint, retry mechanics

### Detailed Design
5. **design/execution-state.md** - Batching, task tracking, suspension
6. **design/serialization.md** - SerDes interface, Jackson implementation
7. **design/error-handling.md** - Exception hierarchy, retry policies
8. **design/wait-operation.md** - Timer operation, service-side completion

### Implementation Guide
9. **design/api-design.md** - Complete API specification
10. **design/implementation-strategy.md** - Java 17 implementation patterns

### Analysis (Background)
- **analysis/concurrency-comparison.md** - vs Temporal/Restate
- **analysis/naming-comparison.md** - Consistency with TS/Python

## 10. Quick Start

### 1. Read This File (5 min)
Understand core concepts and decisions

### 2. Read Handler Registration (10 min)
Choose base class or wrapper pattern

### 3. Read Context Lifecycle (15 min)
Understand envelope parsing and context creation

### 4. Read Step Execution (20 min)
Understand replay, checkpoint, and retry

### 5. Start Coding! (Week 1-3)
Implement PoC following design documents

## 11. Design Principles

1. **Simple things simple** - Blocking API for common case
2. **Complex things possible** - DurableFuture for advanced use
3. **Idiomatic Java** - Use standard patterns (Future, Callable, Builder)
4. **Consistent with SDKs** - Align with TypeScript/Python where possible
5. **Testable** - Easy to mock, no magic
6. **Fresh start** - Not a refactor, clean implementation

---

**Status:** Ready for PoC Implementation  
**Target:** Java 17 LTS, ~500 LOC core code  
**Timeline:** 3 weeks to functional PoC  
**Last Updated:** December 6, 2025
