# Project Context - AWS Lambda Durable Execution Java SDK

**Date:** December 6, 2025  
**Status:** Ready for Implementation  
**Target:** Java 17 LTS, ~500 LOC, 3-week PoC

## What We're Building

A **simple, idiomatic Java SDK** for AWS Lambda Durable Executions that enables developers to write long-running workflows (up to 1 year) with automatic state management, checkpointing, and replay.

This is a **fresh implementation** - not a refactor of existing code.

## The Problem

AWS Lambda Durable Executions is a new service that allows Lambda functions to run for extended periods with:
- Automatic checkpointing and state persistence
- Failure recovery through replay
- Suspension during wait operations (no compute charges)
- Up to 1 year execution time

**We need a Java SDK** so Java developers can use this service with idiomatic patterns.

## What AWS Provides (The Service)

AWS Lambda Durable Executions service handles:
- ✅ Checkpoint storage and management
- ✅ Replay coordination
- ✅ Suspension/resumption
- ✅ Failure recovery
- ✅ State persistence

**We don't need to build these** - AWS already provides them.

## What We're Building (The SDK)

A thin SDK layer that provides:
1. **Simple API** - `DurableContext` with `step()`, `wait()`, `stepAsync()`, `timer()`
2. **Handler registration** - Base class or wrapper function patterns
3. **Replay logic** - Check checkpoint log before executing
4. **Checkpoint batching** - Batch updates (100ms window) to reduce API calls
5. **Retry policies** - Exponential backoff for step failures
6. **Type safety** - Generics with Class<T> parameters

## Core Design Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| **API Style** | Context object | Testable, explicit, aligns with TS/Python SDKs |
| **Async Model** | Blocking + DurableFuture | Simple default, async when needed |
| **Handler Pattern** | Base class OR wrapper | Base class recommended, wrapper for flexibility |
| **Serialization** | Jackson (pluggable) | Standard, familiar, extensible |
| **Concurrency** | CompletableFuture + Semaphore | Idiomatic Java 17 patterns |
| **Checkpointing** | Batched (100ms) | Reduces API calls 10-100x |
| **Java Version** | Java 17 LTS | Lambda support, no virtual threads needed |

## Simple Example

```java
// User writes this
public class OrderProcessor extends DurableHandler<OrderEvent, OrderResult> {
    @Override
    public OrderResult handleRequest(OrderEvent event, DurableContext ctx) {
        // Step 1: Process order
        Order order = ctx.step("process", Order.class, () -> 
            processOrder(event)
        );
        
        // Step 2: Wait for payment (execution suspends, no charges)
        ctx.wait(Duration.ofMinutes(30));
        
        // Step 3: Complete order
        String confirmation = ctx.step("complete", String.class, () ->
            completeOrder(order)
        );
        
        return new OrderResult(confirmation);
    }
}
```

**What happens:**
1. Lambda invokes handler with envelope containing checkpoint log
2. SDK checks if "process" step already completed (replay)
3. If yes, return cached result; if no, execute and checkpoint
4. On `wait()`, SDK suspends execution (Lambda stops, no charges)
5. After 30 minutes, Lambda resumes with updated checkpoint log
6. SDK replays completed steps, continues from "complete" step

## Architecture

```
User Handler (extends DurableHandler)
         ↓
DurableContext (public API)
         ↓
DurableContextImpl (replay logic)
         ↓
ExecutionState (batching, suspension)
         ↓
AWS Lambda Durable Execution API
```

## Key Components to Implement

### Phase 1: Core Classes (Week 1)
1. **DurableContext** - Interface with step(), wait(), stepAsync(), timer()
2. **DurableHandler<I,O>** - Base class for handlers
3. **DurableExecution** - Wrapper function alternative
4. **SerDes** - Serialization interface + Jackson implementation
5. **Config classes** - StepConfig, RetryPolicy

### Phase 2: Execution Logic (Week 2)
1. **DurableContextImpl** - Implements replay logic
2. **ExecutionState** - Checkpoint batching, task tracking, suspension
3. **DurableFuture** - Async operation handle
4. **Retry logic** - Exponential backoff

### Phase 3: Testing & Polish (Week 3)
1. Unit tests
2. Integration tests
3. Example handlers
4. Documentation

## How Replay Works (Critical Concept)

**Traditional approach (Temporal):**
- Re-execute code from beginning
- Intercept all non-deterministic operations
- Complex: custom scheduler, side effects API

**Our approach (Simple):**
- Check checkpoint log before executing
- If operation already completed, return cached result
- If not completed, execute and checkpoint
- No re-execution, just lookup

```java
// Replay logic (simplified)
public <T> T step(String name, Class<T> type, Callable<T> action) {
    String opId = String.valueOf(operationCounter++);
    
    // Check checkpoint log
    Operation existing = state.getOperation(opId);
    if (existing != null && existing.isSucceeded()) {
        // Already done - return cached result
        return deserialize(existing.getResult(), type);
    }
    
    // Not done - execute and checkpoint
    T result = action.call();
    state.checkpoint(opId, name, result);
    return result;
}
```

## Checkpoint Batching (Performance Critical)

**Problem:** Without batching, every step = 1 API call = 10 ops/sec

**Solution:** Batch updates in 100ms window

```java
// Non-blocking queue
pendingUpdates.offer(update);

// Background thread flushes every 100ms
batcher.scheduleAtFixedRate(() -> {
    List<OperationUpdate> batch = new ArrayList<>();
    pendingUpdates.drainTo(batch, 100);  // Max 100 per batch
    
    if (!batch.isEmpty()) {
        lambdaClient.checkpointDurableExecution(batch);
    }
}, 100, 100, TimeUnit.MILLISECONDS);
```

**Result:** 1000 ops/sec (100x improvement)

## Suspension Detection

**When to suspend:** When no active tasks remain

```java
// Track active tasks with Semaphore
Semaphore activeTasks = new Semaphore(0);

// Increment when starting task
activeTasks.release();

// Decrement when completing task
activeTasks.acquire();

// Check if should suspend
if (activeTasks.availablePermits() == 0) {
    // Flush pending checkpoints
    flushCheckpoints();
    
    // Suspend execution
    lambdaClient.suspendDurableExecution(...);
}
```

## Comparison with Other Frameworks

### vs Temporal
- **Temporal:** Builds distributed workflow engine from scratch (~10,000 LOC)
- **Us:** SDK for AWS service (~500 LOC)
- **Temporal:** Custom deterministic threads, complex replay
- **Us:** Checkpoint lookup, standard Java patterns

### vs Restate
- **Restate:** Journal-based state machine, requires Restate server
- **Us:** Similar patterns but Lambda-native, no separate server
- **Both:** Use DurableFuture, context-based API, simple patterns

### Why We're Simple
AWS Lambda Durable Executions **already provides the engine**. We just provide a simple SDK to use it.

## MVP Scope

### ✅ Included
- step() / stepAsync() - Execute with replay
- wait() / timer() - Suspend execution
- Checkpoint batching - 100ms window
- Exponential backoff retry
- Base class + wrapper patterns
- Jackson serialization

### ❌ Post-MVP
- parallel() - Parallel execution
- createCallback() - External callbacks
- invoke() - Lambda invocation
- Advanced retry strategies
- Metrics/observability

## Success Criteria

✅ **Functional**
- Step execution with replay works
- Wait operations suspend/resume correctly
- Checkpoint batching reduces API calls
- Retry with exponential backoff works

✅ **Quality**
- ~500 LOC core implementation
- > 80% test coverage
- All unit tests pass
- Integration tests pass

✅ **Usability**
- Simple handler examples work
- API is intuitive
- Error messages are clear

## Key Files to Read

**Start here:**
1. **README.md** - Quick start and document index
2. **00-DESIGN-SUMMARY.md** - High-level overview (5 min)
3. **api-design.md** - Complete API specification (15 min)
4. **implementation-strategy.md** - Implementation guidance (20 min)

**Reference during implementation:**
5. **handler-registration.md** - Handler patterns
6. **context-lifecycle.md** - Envelope parsing, context creation
7. **step-execution.md** - Replay, checkpoint, retry
8. **execution-state.md** - Batching, task tracking, suspension
9. **serialization.md** - SerDes interface
10. **error-handling.md** - Exceptions and retry
11. **wait-operation.md** - Timer and suspension

## Dependencies

```xml
<!-- AWS Lambda Core -->
<dependency>
    <groupId>com.amazonaws</groupId>
    <artifactId>aws-lambda-java-core</artifactId>
    <version>1.2.3</version>
</dependency>

<!-- AWS SDK for Lambda Client -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>lambda</artifactId>
    <version>2.20.0</version>
</dependency>

<!-- Jackson for JSON -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.15.0</version>
</dependency>
```

## Package Structure

```
com.amazonaws.lambda.durable/
├── DurableContext.java              # Main API interface
├── DurableHandler.java              # Base class for handlers
├── DurableExecution.java            # Wrapper function utility
├── DurableFuture.java               # Async operation handle
├── DurableExecutionException.java   # Base exception
├── StepFailedException.java         # Step failure exception
├── CheckpointException.java         # Checkpoint failure exception
├── config/
│   ├── StepConfig.java              # Step configuration
│   └── RetryPolicy.java             # Retry configuration
├── serde/
│   ├── SerDes.java                  # Serialization interface
│   └── JacksonSerDes.java           # Jackson implementation
└── internal/
    ├── DurableContextImpl.java      # Context implementation
    ├── ExecutionState.java          # State management
    ├── DurableFutureImpl.java       # Future implementation
    └── ... (other internal classes)
```

## Common Pitfalls to Avoid

❌ **Don't re-execute code during replay** - Just lookup cached results  
❌ **Don't use complex thread coordination** - Use simple Semaphore  
❌ **Don't build custom state machines** - Use CompletableFuture  
❌ **Don't checkpoint synchronously** - Use batching (100ms window)  
❌ **Don't forget type erasure** - Always pass Class<T> parameters  

## Questions to Ask

1. **"How does replay work?"** → Checkpoint log lookup, not re-execution
2. **"Why so simple?"** → AWS provides the engine, we provide the API
3. **"Why not like Temporal?"** → Different architecture, different needs
4. **"What about parallel execution?"** → Post-MVP feature
5. **"How to test?"** → Mock DurableContext, standard JUnit/Mockito

## Next Steps

1. Set up Maven/Gradle project structure
2. Create package hierarchy
3. Implement Phase 1 (Core classes)
4. Implement Phase 2 (Execution logic)
5. Implement Phase 3 (Testing & polish)
6. Create example handlers
7. Write documentation

---

**Timeline:** 3 weeks to functional PoC  
**Target:** ~500 LOC core code, Java 17 LTS  
**Status:** Design complete, ready for implementation  
**Last Updated:** December 6, 2025
