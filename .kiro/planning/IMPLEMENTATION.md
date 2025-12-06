# Java SDK Implementation Plan

**Target:** AWS Lambda Durable Functions SDK - Level 1 (Minimal)  
**Date:** December 8, 2025  
**Status:** Ready to Implement

## Overview

This document provides a step-by-step implementation plan for the Java SDK. Each increment is independently testable and builds on previous increments. The plan is designed to ensure understanding at each step and enable local testing without AWS infrastructure.

## Key Principles

1. **Each increment is independently testable**
2. **Each increment adds one clear feature**
3. **Tests verify behavior, not implementation**
4. **Can commit after each increment**
5. **Can demo progress at any point**

## Implementation Phases

- **Phase 1:** Foundation (No AWS SDK Yet)
- **Phase 2:** Mock AWS Types (Testable Locally)
- **Phase 3:** State Management (In-Memory)
- **Phase 4:** DurableContext (Core Operations)
- **Phase 5:** Execute Method
- **Phase 6:** Async Operations
- **Phase 7:** Checkpoint Batching
- **Phase 8:** Entry Points & Polish
- **Phase 9:** Real AWS SDK Integration

---

## Phase 1: Foundation (No AWS SDK Yet)

### Increment 1: Project Setup

**Goal:** Create Maven project with basic structure.

**Files to Create:**
- `pom.xml`
- `src/main/java/com/amazonaws/lambda/durable/` (package structure)
- `src/test/java/com/amazonaws/lambda/durable/` (test structure)

**Dependencies:**
```xml
<dependencies>
    <!-- Jackson for JSON -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.16.0</version>
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.datatype</groupId>
        <artifactId>jackson-datatype-jsr310</artifactId>
        <version>2.16.0</version>
    </dependency>
    
    <!-- JUnit 5 for testing -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.1</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

**Test:** `mvn clean compile` succeeds

**Commit:** "Increment 1: Project setup"

---

### Increment 2: ExecutionStatus Enum

**Goal:** Create enum for execution output status.

**File:** `ExecutionStatus.java`

**Code:**
```java
package com.amazonaws.lambda.durable;

public enum ExecutionStatus {
    SUCCEEDED,
    FAILED,
    PENDING;
    
    @Override
    public String toString() {
        return name();
    }
}
```

**Test:**
```java
@Test
void testExecutionStatus() {
    assertEquals("SUCCEEDED", ExecutionStatus.SUCCEEDED.toString());
    assertEquals("FAILED", ExecutionStatus.FAILED.toString());
    assertEquals("PENDING", ExecutionStatus.PENDING.toString());
}
```

**Commit:** "Increment 2: ExecutionStatus enum"

---

### Increment 3: ErrorObject Record

**Goal:** Create error representation with stack traces.

**File:** `ErrorObject.java`

**Code:**
```java
package com.amazonaws.lambda.durable;

import java.util.Arrays;
import java.util.List;

public record ErrorObject(
    String errorType,
    String errorMessage,
    List<String> stackTrace
) {
    public static ErrorObject fromException(Throwable e) {
        return new ErrorObject(
            e.getClass().getSimpleName(),
            e.getMessage(),
            Arrays.stream(e.getStackTrace())
                .map(StackTraceElement::toString)
                .limit(50)
                .toList()
        );
    }
}
```

**Test:**
```java
@Test
void testErrorObjectFromException() {
    var exception = new RuntimeException("Test error");
    var error = ErrorObject.fromException(exception);
    
    assertEquals("RuntimeException", error.errorType());
    assertEquals("Test error", error.errorMessage());
    assertFalse(error.stackTrace().isEmpty());
}
```

**Commit:** "Increment 3: ErrorObject record"

---

### Increment 4: Output Envelope

**Goal:** Create output envelope with factory methods.

**File:** `DurableExecutionOutput.java`

**Code:**
```java
package com.amazonaws.lambda.durable;

public record DurableExecutionOutput(
    ExecutionStatus status,
    String result,
    ErrorObject error
) {
    public static DurableExecutionOutput success(String result) {
        return new DurableExecutionOutput(ExecutionStatus.SUCCEEDED, result, null);
    }
    
    public static DurableExecutionOutput pending() {
        return new DurableExecutionOutput(ExecutionStatus.PENDING, null, null);
    }
    
    public static DurableExecutionOutput failure(ErrorObject error) {
        return new DurableExecutionOutput(ExecutionStatus.FAILED, null, error);
    }
}
```

**Test:**
```java
@Test
void testOutputEnvelope() {
    var success = DurableExecutionOutput.success("result");
    assertEquals(ExecutionStatus.SUCCEEDED, success.status());
    assertEquals("result", success.result());
    
    var pending = DurableExecutionOutput.pending();
    assertEquals(ExecutionStatus.PENDING, pending.status());
    
    var error = ErrorObject.fromException(new RuntimeException("fail"));
    var failure = DurableExecutionOutput.failure(error);
    assertEquals(ExecutionStatus.FAILED, failure.status());
}
```

**Commit:** "Increment 4: DurableExecutionOutput envelope"

---

### Increment 5: SerDes Interface + Jackson Implementation

**Goal:** Create serialization interface with default implementation.

**Files:** `SerDes.java`, `JacksonSerDes.java`

**Code:**
```java
// SerDes.java
package com.amazonaws.lambda.durable;

public interface SerDes {
    String serialize(Object value);
    <T> T deserialize(String data, Class<T> type);
}

// JacksonSerDes.java
package com.amazonaws.lambda.durable;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class JacksonSerDes implements SerDes {
    private final ObjectMapper mapper;
    
    public JacksonSerDes() {
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    
    @Override
    public String serialize(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }
    
    @Override
    public <T> T deserialize(String data, Class<T> type) {
        if (data == null) return null;
        try {
            return mapper.readValue(data, type);
        } catch (Exception e) {
            throw new RuntimeException("Deserialization failed", e);
        }
    }
}
```

**Test:**
```java
@Test
void testSerDesRoundTrip() {
    var serDes = new JacksonSerDes();
    
    var original = new TestData("test", 42);
    var json = serDes.serialize(original);
    var deserialized = serDes.deserialize(json, TestData.class);
    
    assertEquals(original, deserialized);
}

record TestData(String name, int value) {}
```

**Commit:** "Increment 5: SerDes interface and Jackson implementation"

---

### Increment 6: Exception Hierarchy

**Goal:** Create exception classes for error handling.

**Files:** `DurableExecutionException.java`, `StepException.java`, `SuspendExecutionException.java`

**Code:**
```java
// DurableExecutionException.java
package com.amazonaws.lambda.durable;

public class DurableExecutionException extends RuntimeException {
    private final String errorType;
    
    public DurableExecutionException(String message, String errorType) {
        super(message);
        this.errorType = errorType;
    }
    
    public DurableExecutionException(String message, String errorType, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }
    
    public String getErrorType() {
        return errorType;
    }
}

// StepException.java
package com.amazonaws.lambda.durable;

public class StepException extends DurableExecutionException {
    public StepException(String message) {
        super(message, "StepException");
    }
    
    public StepException(String message, Throwable cause) {
        super(message, "StepException", cause);
    }
}

// SuspendExecutionException.java
package com.amazonaws.lambda.durable;

public class SuspendExecutionException extends DurableExecutionException {
    public SuspendExecutionException() {
        super("Execution suspended for wait operation", "SuspendExecution");
    }
}
```

**Test:**
```java
@Test
void testExceptionHierarchy() {
    var stepEx = new StepException("step failed");
    assertEquals("StepException", stepEx.getErrorType());
    
    var suspendEx = new SuspendExecutionException();
    assertEquals("SuspendExecution", suspendEx.getErrorType());
}
```

**Commit:** "Increment 6: Exception hierarchy"

---

## Phase 2: Mock AWS Types (Testable Locally)

### Increment 7: Mock Operation Classes

**Goal:** Create minimal mock of AWS SDK Operation types for local testing.

**Files:** `Operation.java`, `OperationType.java`, `OperationStatus.java`

**Code:**
```java
// Operation.java
package com.amazonaws.lambda.durable.model;

public record Operation(
    String id,
    OperationType type,
    OperationStatus status,
    String result
) {}

// OperationType.java
package com.amazonaws.lambda.durable.model;

public enum OperationType {
    EXECUTION,
    STEP,
    WAIT,
    CALLBACK,
    CHAINED_INVOKE,
    CONTEXT
}

// OperationStatus.java
package com.amazonaws.lambda.durable.model;

public enum OperationStatus {
    STARTED,
    PENDING,
    READY,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    STOPPED
}
```

**Note:** These will be replaced with real AWS SDK types in Increment 26.

**Test:**
```java
@Test
void testOperationCreation() {
    var op = new Operation("1", OperationType.STEP, OperationStatus.SUCCEEDED, "result");
    assertEquals("1", op.id());
    assertEquals(OperationType.STEP, op.type());
}
```

**Commit:** "Increment 7: Mock Operation classes"

---

### Increment 8: Mock OperationUpdate

**Goal:** Create operation update structure for checkpointing.

**Files:** `OperationUpdate.java`, `OperationAction.java`

**Code:**
```java
// OperationUpdate.java
package com.amazonaws.lambda.durable.model;

public record OperationUpdate(
    String id,
    OperationType type,
    OperationAction action,
    String payload
) {}

// OperationAction.java
package com.amazonaws.lambda.durable.model;

public enum OperationAction {
    START,
    SUCCEED,
    FAIL,
    RETRY,
    CANCEL
}
```

**Test:**
```java
@Test
void testOperationUpdate() {
    var update = new OperationUpdate("1", OperationType.STEP, OperationAction.SUCCEED, "result");
    assertEquals("1", update.id());
    assertEquals(OperationAction.SUCCEED, update.action());
}
```

**Commit:** "Increment 8: Mock OperationUpdate"

---

### Increment 9: DurableExecutionClient Interface

**Goal:** Create interface for checkpoint operations.

**Files:** `DurableExecutionClient.java`, response records

**Code:**
```java
// DurableExecutionClient.java
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.model.*;
import java.util.List;

public interface DurableExecutionClient {
    CheckpointResponse checkpoint(String arn, String token, List<OperationUpdate> updates);
    GetExecutionStateResponse getExecutionState(String arn, String marker);
}

// CheckpointResponse.java
public record CheckpointResponse(
    String checkpointToken,
    NewExecutionState newExecutionState
) {
    public record NewExecutionState(
        List<Operation> operations
    ) {}
}

// GetExecutionStateResponse.java
public record GetExecutionStateResponse(
    List<Operation> operations,
    String nextMarker
) {}
```

**Test:**
```java
@Test
void testClientInterface() {
    // Create mock implementation
    DurableExecutionClient client = new MockClient();
    assertNotNull(client);
}
```

**Commit:** "Increment 9: DurableExecutionClient interface"

---

## Phase 3: State Management (In-Memory)

### Increment 10: InMemoryCheckpointStorage

**Goal:** Implement test checkpoint storage that simulates Lambda API.

**File:** `InMemoryCheckpointStorage.java`

**Code:**
```java
package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.*;
import com.amazonaws.lambda.durable.model.*;
import java.util.*;
import java.util.concurrent.*;

public class InMemoryCheckpointStorage implements DurableExecutionClient {
    private final Map<String, Operation> operations = new ConcurrentHashMap<>();
    private final AtomicReference<String> checkpointToken = 
        new AtomicReference<>(UUID.randomUUID().toString());
    
    @Override
    public CheckpointResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
        // Apply updates to in-memory storage
        updates.forEach(this::applyUpdate);
        
        // Generate new token
        var newToken = UUID.randomUUID().toString();
        checkpointToken.set(newToken);
        
        return new CheckpointResponse(
            newToken,
            new CheckpointResponse.NewExecutionState(
                new ArrayList<>(operations.values())
            )
        );
    }
    
    @Override
    public GetExecutionStateResponse getExecutionState(String arn, String marker) {
        return new GetExecutionStateResponse(
            new ArrayList<>(operations.values()),
            null
        );
    }
    
    private void applyUpdate(OperationUpdate update) {
        var operation = toOperation(update);
        operations.put(update.id(), operation);
    }
    
    private Operation toOperation(OperationUpdate update) {
        return new Operation(
            update.id(),
            update.type(),
            deriveStatus(update.action()),
            update.payload()
        );
    }
    
    private OperationStatus deriveStatus(OperationAction action) {
        return switch (action) {
            case START -> OperationStatus.STARTED;
            case SUCCEED -> OperationStatus.SUCCEEDED;
            case FAIL -> OperationStatus.FAILED;
            case RETRY -> OperationStatus.PENDING;
            case CANCEL -> OperationStatus.CANCELLED;
        };
    }
}
```

**Test:**
```java
@Test
void testInMemoryStorage() {
    var storage = new InMemoryCheckpointStorage();
    
    var update = new OperationUpdate("1", OperationType.STEP, OperationAction.SUCCEED, "result");
    var response = storage.checkpoint("arn", "token", List.of(update));
    
    assertNotNull(response.checkpointToken());
    assertEquals(1, response.newExecutionState().operations().size());
}
```

**Commit:** "Increment 10: InMemoryCheckpointStorage"

---

### Increment 11: ExecutionState (No Batching Yet)

**Goal:** Create state management class with direct checkpointing.

**File:** `ExecutionState.java`

**Code:**
```java
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

class ExecutionState {
    private final String durableExecutionArn;
    private final Map<String, Operation> checkpointLog;
    private final DurableExecutionClient client;
    private final AtomicReference<String> checkpointToken;
    
    ExecutionState(String arn, String token, List<Operation> operations, 
                   DurableExecutionClient client) {
        this.durableExecutionArn = arn;
        this.checkpointToken = new AtomicReference<>(token);
        this.checkpointLog = new ConcurrentHashMap<>();
        this.client = client;
        
        // Load initial operations
        operations.forEach(op -> checkpointLog.put(op.id(), op));
    }
    
    void checkpoint(OperationUpdate update) {
        // Direct call to client (no batching yet)
        var response = client.checkpoint(
            durableExecutionArn,
            checkpointToken.get(),
            List.of(update)
        );
        
        // Update token
        checkpointToken.set(response.checkpointToken());
        
        // Update local state
        response.newExecutionState().operations()
            .forEach(op -> checkpointLog.put(op.id(), op));
    }
    
    String getDurableExecutionArn() {
        return durableExecutionArn;
    }
    
    String getCheckpointToken() {
        return checkpointToken.get();
    }
    
    Optional<Operation> getOperation(String operationId) {
        return Optional.ofNullable(checkpointLog.get(operationId));
    }
}
```

**Test:**
```java
@Test
void testExecutionState() {
    var storage = new InMemoryCheckpointStorage();
    var state = new ExecutionState("arn", "token", List.of(), storage);
    
    var update = new OperationUpdate("1", OperationType.STEP, OperationAction.SUCCEED, "result");
    state.checkpoint(update);
    
    var op = state.getOperation("1");
    assertTrue(op.isPresent());
    assertEquals(OperationStatus.SUCCEEDED, op.get().status());
}
```

**Commit:** "Increment 11: ExecutionState with direct checkpointing"

---

## Phase 4: DurableContext (Core Operations)

### Increment 12: DurableContext Structure

**Goal:** Create DurableContext class with basic structure.

**File:** `DurableContext.java`

**Code:**
```java
package com.amazonaws.lambda.durable;

import com.amazonaws.aws.lambda.runtime.Context;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class DurableContext {
    // Shared thread pool across all invocations
    private static final ExecutorService executorService = 
        Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2,
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                
                @Override
                public Thread newThread(Runnable r) {
                    var thread = new Thread(r);
                    thread.setName("durable-execution-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            }
        );
    
    private final ExecutionState state;
    private final SerDes serDes;
    private final Context lambdaContext;
    private final AtomicInteger operationCounter;
    
    DurableContext(ExecutionState state, SerDes serDes, Context lambdaContext) {
        this.state = state;
        this.serDes = serDes;
        this.lambdaContext = lambdaContext;
        this.operationCounter = new AtomicInteger(0);
    }
    
    public Context getLambdaContext() {
        return lambdaContext;
    }
    
    private String nextOperationId() {
        return String.valueOf(operationCounter.incrementAndGet());
    }
}
```

**Test:**
```java
@Test
void testDurableContextCreation() {
    var storage = new InMemoryCheckpointStorage();
    var state = new ExecutionState("arn", "token", List.of(), storage);
    var serDes = new JacksonSerDes();
    var context = new DurableContext(state, serDes, null);
    
    assertNotNull(context);
}
```

**Commit:** "Increment 12: DurableContext structure"

---

### Increment 13: Step Operation (Synchronous)

**Goal:** Implement synchronous step operation with replay.

**Add to DurableContext:**
```java
public <T> T step(String name, Class<T> resultType, Supplier<T> func) {
    var operationId = nextOperationId();
    
    // Check replay
    var existing = state.getOperation(operationId);
    if (existing.isPresent() && existing.get().status() == OperationStatus.SUCCEEDED) {
        return serDes.deserialize(existing.get().result(), resultType);
    }
    
    // Execute
    var result = func.get();
    
    // Checkpoint
    checkpoint(operationId, result);
    
    return result;
}

private void checkpoint(String operationId, Object result) {
    var update = new OperationUpdate(
        operationId,
        OperationType.STEP,
        OperationAction.SUCCEED,
        serDes.serialize(result)
    );
    
    state.checkpoint(update);
}
```

**Test:**
```java
@Test
void testStepExecution() {
    var storage = new InMemoryCheckpointStorage();
    var state = new ExecutionState("arn", "token", List.of(), storage);
    var serDes = new JacksonSerDes();
    var context = new DurableContext(state, serDes, null);
    
    var result = context.step("test", String.class, () -> "hello");
    assertEquals("hello", result);
}

@Test
void testStepReplay() {
    var storage = new InMemoryCheckpointStorage();
    var state = new ExecutionState("arn", "token", List.of(), storage);
    var serDes = new JacksonSerDes();
    var context = new DurableContext(state, serDes, null);
    
    // First execution
    var result1 = context.step("test", String.class, () -> "hello");
    assertEquals("hello", result1);
    
    // Create new context with same state (simulates replay)
    var context2 = new DurableContext(state, serDes, null);
    var result2 = context2.step("test", String.class, () -> "world");
    
    // Should return cached result
    assertEquals("hello", result2);
}
```

**Commit:** "Increment 13: Step operation with replay"

---

### Increment 14: Wait Operation

**Goal:** Implement wait operation that suspends execution.

**Add to DurableContext:**
```java
import java.time.Duration;

public void wait(Duration duration) {
    var operationId = nextOperationId();
    
    // Check replay
    var existing = state.getOperation(operationId);
    if (existing.isPresent() && existing.get().status() == OperationStatus.SUCCEEDED) {
        return; // Wait already completed
    }
    
    // Checkpoint and suspend
    checkpoint(operationId, duration);
    throw new SuspendExecutionException();
}
```

**Test:**
```java
@Test
void testWaitSuspends() {
    var storage = new InMemoryCheckpointStorage();
    var state = new ExecutionState("arn", "token", List.of(), storage);
    var serDes = new JacksonSerDes();
    var context = new DurableContext(state, serDes, null);
    
    assertThrows(SuspendExecutionException.class, 
        () -> context.wait(Duration.ofSeconds(5)));
}
```

**Commit:** "Increment 14: Wait operation"

---

## Phase 5: Execute Method

### Increment 15: Input Envelope

**Goal:** Create input envelope structure.

**File:** `DurableExecutionInput.java`

**Code:**
```java
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.model.Operation;
import java.util.List;

public record DurableExecutionInput(
    String durableExecutionArn,
    String checkpointToken,
    InitialExecutionState initialExecutionState
) {
    public record InitialExecutionState(
        List<Operation> operations,
        String nextMarker
    ) {}
}
```

**Test:**
```java
@Test
void testInputEnvelope() {
    var input = new DurableExecutionInput(
        "arn",
        "token",
        new DurableExecutionInput.InitialExecutionState(List.of(), null)
    );
    
    assertEquals("arn", input.durableExecutionArn());
}
```

**Commit:** "Increment 15: Input envelope"

---

### Increment 16: Execute Method (Basic)

**Goal:** Implement basic execute method without EXECUTION operation handling.

**File:** `DurableExecution.java`

**Code:**
```java
package com.amazonaws.lambda.durable;

import com.amazonaws.aws.lambda.runtime.Context;
import java.util.function.BiFunction;

public class DurableExecution {
    static <I, O> DurableExecutionOutput execute(
        DurableExecutionInput input,
        Context lambdaContext,
        BiFunction<I, DurableContext, O> handler
    ) {
        // Create state
        var operations = input.initialExecutionState().operations();
        var client = new InMemoryCheckpointStorage(); // For now
        var state = new ExecutionState(
            input.durableExecutionArn(),
            input.checkpointToken(),
            operations,
            client
        );
        
        // Create context
        var serDes = new JacksonSerDes();
        var context = new DurableContext(state, serDes, lambdaContext);
        
        // Execute handler (assume input is already extracted)
        try {
            var result = handler.apply(userInput, context);
            return DurableExecutionOutput.success(serDes.serialize(result));
        } catch (SuspendExecutionException e) {
            return DurableExecutionOutput.pending();
        } catch (Exception e) {
            return DurableExecutionOutput.failure(ErrorObject.fromException(e));
        }
    }
}
```

**Test:** End-to-end with mock input

**Commit:** "Increment 16: Basic execute method"

---

### Increment 17: EXECUTION Operation Handling

**Goal:** Add EXECUTION operation extraction and validation.

**Add to execute():**
```java
// Validate and extract EXECUTION operation
if (operations.isEmpty() || operations.get(0).type() != OperationType.EXECUTION) {
    throw new IllegalStateException("First operation must be EXECUTION");
}

var executionOp = operations.get(0);
var userInput = extractUserInput(executionOp, serDes);

private static <I> I extractUserInput(Operation executionOp, SerDes serDes) {
    var inputPayload = executionOp.executionDetails().inputPayload();
    return serDes.deserialize(inputPayload, inputType);
}
```

**Test:** Verify EXECUTION extraction, validation

**Commit:** "Increment 17: EXECUTION operation handling"

---

## Phase 6: Async Operations

### Increment 18: Async Step

**Goal:** Implement stepAsync() with CompletableFuture.

**Add to DurableContext:**
```java
public <T> CompletableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func) {
    var operationId = nextOperationId();
    
    // Check replay
    var existing = state.getOperation(operationId);
    if (existing.isPresent() && existing.get().status() == OperationStatus.SUCCEEDED) {
        return CompletableFuture.completedFuture(
            serDes.deserialize(existing.get().result(), resultType)
        );
    }
    
    // Execute async
    return CompletableFuture.supplyAsync(() -> {
        var result = func.get();
        checkpoint(operationId, result);
        return result;
    }, executorService);
}
```

**Test:** Verify async execution, composition

**Commit:** "Increment 18: Async step operation"

---

### Increment 19: DurableFuture Wrapper

**Goal:** Wrap CompletableFuture for future extensibility.

**File:** `DurableFuture.java`

**Code:**
```java
package com.amazonaws.lambda.durable;

import java.util.concurrent.*;

public class DurableFuture<T> implements Future<T> {
    private final CompletableFuture<T> delegate;
    
    DurableFuture(CompletableFuture<T> delegate) {
        this.delegate = delegate;
    }
    
    public T join() {
        return delegate.join();
    }
    
    @Override
    public T get() throws InterruptedException, ExecutionException {
        return delegate.get();
    }
    
    @Override
    public T get(long timeout, TimeUnit unit) 
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.get(timeout, unit);
    }
    
    public <U> DurableFuture<U> thenCompose(Function<T, DurableFuture<U>> fn) {
        return new DurableFuture<>(
            delegate.thenCompose(t -> fn.apply(t).delegate)
        );
    }
    
    CompletableFuture<T> toCompletableFuture() {
        return delegate;
    }
}
```

**Test:** Verify delegation, composition

**Commit:** "Increment 19: DurableFuture wrapper"

---

## Phase 7: Checkpoint Batching

### Increment 20: CheckpointBatcher

**Goal:** Implement checkpoint batching with CompletableFuture tracking and immediate processing.

**Design:** Matches TypeScript SDK's setImmediate pattern and official AWS Java SDK's CompletableFuture approach.

**File:** `CheckpointBatcher.java`

**Code:**
```java
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.model.OperationUpdate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

class CheckpointBatcher {
    private static final int MAX_BATCH_SIZE_BYTES = 750 * 1024; // 750KB
    
    private final BlockingQueue<CheckpointRequest> queue = new LinkedBlockingQueue<>();
    private final DurableExecutionClient client;
    private final ExecutionState state;
    private final ExecutorService executor;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    record CheckpointRequest(OperationUpdate update, CompletableFuture<Void> completion) {}
    
    CheckpointBatcher(DurableExecutionClient client, ExecutionState state, ExecutorService executor) {
        this.client = client;
        this.state = state;
        this.executor = executor;
    }
    
    CompletableFuture<Void> add(OperationUpdate update) {
        var future = new CompletableFuture<Void>();
        queue.offer(new CheckpointRequest(update, future));
        
        // Trigger immediate processing if not already processing
        if (isProcessing.compareAndSet(false, true)) {
            executor.submit(this::processQueue);
        }
        
        return future;
    }
    
    void shutdown() {
        // Drain remaining items and fail them
        var remaining = new ArrayList<CheckpointRequest>();
        queue.drainTo(remaining);
        remaining.forEach(req -> 
            req.completion().completeExceptionally(new IllegalStateException("Batcher shutdown")));
    }
    
    private void processQueue() {
        try {
            var batch = collectBatch();
            if (batch.isEmpty()) {
                return;
            }
            
            // Extract updates
            var updates = batch.stream()
                .map(CheckpointRequest::update)
                .toList();
            
            // Make API call
            var response = client.checkpoint(
                state.getDurableExecutionArn(),
                state.getCheckpointToken(),
                updates
            );
            
            // Update state
            state.updateCheckpointToken(response.checkpointToken());
            state.updateOperations(response.newExecutionState().operations());
            
            // Complete all futures
            batch.forEach(req -> req.completion().complete(null));
            
        } catch (Exception e) {
            // Fail all futures in current batch
            var batch = new ArrayList<CheckpointRequest>();
            queue.drainTo(batch);
            batch.forEach(req -> req.completion().completeExceptionally(e));
        } finally {
            isProcessing.set(false);
            
            // Check if more items arrived while processing
            if (!queue.isEmpty() && isProcessing.compareAndSet(false, true)) {
                executor.submit(this::processQueue);
            }
        }
    }
    
    private List<CheckpointRequest> collectBatch() {
        var batch = new ArrayList<CheckpointRequest>();
        var currentSize = 0;
        
        // Drain queue up to size limit
        CheckpointRequest req;
        while ((req = queue.poll()) != null) {
            var itemSize = estimateSize(req.update());
            
            // If adding this would exceed limit and we have items, stop
            if (currentSize + itemSize > MAX_BATCH_SIZE_BYTES && !batch.isEmpty()) {
                // Put it back for next batch
                queue.offer(req);
                break;
            }
            
            batch.add(req);
            currentSize += itemSize;
        }
        
        return batch;
    }
    
    private int estimateSize(OperationUpdate update) {
        return update.id().length() + 
               update.type().toString().length() + 
               update.action().toString().length() + 
               (update.payload() != null ? update.payload().length() : 0) +
               100; // Overhead
    }
}
```

**Key Features:**
- Returns `CompletableFuture<Void>` for sync/async support
- Immediate processing via executor (not time-based)
- 750KB batch size limit (matches TypeScript/Python SDKs)
- AtomicBoolean ensures single-threaded processing
- Opportunistic batching via thread scheduling

**Test:** Verify batching, size limits, future completion

**Commit:** "Increment 20: CheckpointBatcher with CompletableFuture"

---

### Increment 21: Integrate Batching into ExecutionState

**Goal:** Update ExecutionState to use CheckpointBatcher with sync/async methods.

**Update ExecutionState:**
```java
class ExecutionState {
    private final CheckpointBatcher batcher;
    
    ExecutionState(String arn, String token, List<Operation> operations, 
                   DurableExecutionClient client) {
        this.durableExecutionArn = arn;
        this.checkpointToken = new AtomicReference<>(token);
        this.checkpointLog = new ConcurrentHashMap<>();
        this.client = client;
        
        operations.forEach(op -> checkpointLog.put(op.id(), op));
        
        // Create batcher with shared thread pool
        var executor = Executors.newSingleThreadExecutor();
        this.batcher = new CheckpointBatcher(client, this, executor);
    }
    
    // Synchronous checkpoint - waits for completion
    void checkpoint(OperationUpdate update) {
        var future = batcher.add(update);
        future.join();  // Block until done
    }
    
    // Asynchronous checkpoint - returns immediately
    void checkpointAsync(OperationUpdate update) {
        batcher.add(update);  // Don't wait
    }
    
    void updateCheckpointToken(String newToken) {
        checkpointToken.set(newToken);
    }
    
    void updateOperations(List<Operation> operations) {
        operations.forEach(op -> checkpointLog.put(op.id(), op));
    }
}
```

**Key Changes:**
- `checkpoint()` blocks on future.join() for synchronous operations
- `checkpointAsync()` returns immediately for async operations
- Matches official AWS Java SDK's CompletableFuture pattern

**Test:** Verify sync/async behavior, batching works end-to-end

**Commit:** "Increment 21: Integrate checkpoint batching"

---

## Phase 8: Entry Points & Polish

### Increment 22: DurableHandler Base Class

**Goal:** Create base class pattern for handlers.

**File:** `DurableHandler.java`

**Code:**
```java
package com.amazonaws.lambda.durable;

import com.amazonaws.aws.lambda.runtime.*;

public abstract class DurableHandler<I, O> 
    implements RequestHandler<DurableExecutionInput, DurableExecutionOutput> {
    
    @Override
    public final DurableExecutionOutput handleRequest(
        DurableExecutionInput input, Context context
    ) {
        return DurableExecution.execute(input, context, this::handle);
    }
    
    protected abstract O handle(I input, DurableContext context);
}
```

**Test:** Create test handler, verify it works

**Commit:** "Increment 22: DurableHandler base class"

---

### Increment 23: Wrapper Pattern

**Goal:** Add wrapper pattern for handlers.

**Add to DurableExecution:**
```java
public static <I, O> RequestHandler<DurableExecutionInput, DurableExecutionOutput> wrap(
    BiFunction<I, DurableContext, O> handler
) {
    return (input, context) -> execute(input, context, handler);
}
```

**Test:** Verify both patterns work identically

**Commit:** "Increment 23: Wrapper pattern"

---

### Increment 24: Pagination Support

**Goal:** Add pagination for large execution state.

**Add to DurableExecution:**
```java
private static List<Operation> loadAllOperations(DurableExecutionInput input, DurableExecutionClient client) {
    var operations = new ArrayList<>(input.initialExecutionState().operations());
    var nextMarker = input.initialExecutionState().nextMarker();
    
    while (nextMarker != null) {
        var response = client.getExecutionState(input.durableExecutionArn(), nextMarker);
        operations.addAll(response.operations());
        nextMarker = response.nextMarker();
    }
    
    return operations;
}
```

**Test:** Mock multiple pages, verify all loaded

**Commit:** "Increment 24: Pagination support"

---

### Increment 25: LocalDurableTestRunner

**Goal:** Create test runner for local testing.

**File:** `LocalDurableTestRunner.java`

**Code:**

```java
package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.client.LocalMemoryExecutionClient;

import java.util.function.BiFunction;

public class LocalDurableTestRunner<I, O> {
    private final BiFunction<I, DurableContext, O> handler;
    private final LocalMemoryExecutionClient storage;

    public LocalDurableTestRunner(BiFunction<I, DurableContext, O> handler) {
        this.handler = handler;
        this.storage = new LocalMemoryExecutionClient();
    }

    public TestResult<O> run(I input) {
        var durableInput = createDurableInput(input);
        var result = DurableExecution.execute(durableInput, mockLambdaContext(), handler);

        return new TestResult<>(
                result,
                storage.getOperations(),
                getInvocationCount()
        );
    }
}
```

**Test:** Run complete workflows locally

**Commit:** "Increment 25: LocalDurableTestRunner"

---

## Phase 9: Real AWS SDK Integration

### Increment 26: Replace Mock Types with AWS SDK

**Goal:** Replace mock Operation types with real AWS SDK types.

**Changes:**
- Remove `com.amazonaws.lambda.durable.model` package
- Import from `software.amazon.awssdk.services.lambda.model.*`
- Update all references

**Test:** Verify everything still compiles and works

**Commit:** "Increment 26: Use real AWS SDK types"

---

### Increment 27: Real Lambda Client Adapter

**Goal:** Create adapter for real Lambda client.

**File:** `LambdaClientAdapter.java`

**Code:**
```java
package com.amazonaws.lambda.durable;

import software.amazon.awssdk.services.lambda.LambdaClient;

public class LambdaClientAdapter implements DurableExecutionClient {
    private final LambdaClient lambdaClient;
    
    public LambdaClientAdapter(LambdaClient lambdaClient) {
        this.lambdaClient = lambdaClient;
    }
    
    @Override
    public CheckpointResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
        // Call real Lambda API
        var request = CheckpointDurableExecutionRequest.builder()
            .durableExecutionArn(arn)
            .checkpointToken(token)
            .updates(updates)
            .build();
        
        var response = lambdaClient.checkpointDurableExecution(request);
        
        return new CheckpointResponse(
            response.checkpointToken(),
            new CheckpointResponse.NewExecutionState(response.newExecutionState().operations())
        );
    }
}
```

**Test:** Integration test with real AWS (optional)

**Commit:** "Increment 27: Real Lambda client adapter"

---

## Key Milestones

| After Increment | Milestone |
|----------------|-----------|
| 6 | Foundation complete, can serialize/deserialize |
| 11 | State management works in-memory |
| 14 | Core operations work (step, wait) |
| 17 | Full execute flow works |
| 21 | Checkpoint batching works |
| 25 | Complete local testing infrastructure |
| 27 | Ready for production use |

---

## Recommended Path

### **Week 1: Foundation**
- Increments 1-6: Get project building, basic types working

### **Week 2: Core Functionality**
- Increments 7-14: State management, core operations

### **Week 3: Integration**
- Increments 15-21: Execute method, async, batching

### **Week 4: Polish & Production**
- Increments 22-27: Entry points, testing, AWS integration

---

## Testing Strategy

### Unit Tests
- Test each increment independently
- Mock dependencies
- Focus on behavior, not implementation

### Integration Tests
- Test with InMemoryCheckpointStorage
- Verify end-to-end flows
- Test replay scenarios

### Local Testing
- Use LocalDurableTestRunner
- Test complete workflows
- Verify determinism

### AWS Integration Tests (Optional)
- Test with real Lambda
- Verify checkpoint API integration
- Test pagination

---

## Next Steps

**Ready to start with Increment 1: Project Setup?**

Run: `mvn archetype:generate` or create `pom.xml` manually.

---

## Future Considerations

### Discussion: Sync/Async Delegation Pattern

**Current Implementation:**
- `step()` - synchronous, direct implementation
- `stepAsync()` - asynchronous, separate implementation using CompletableFuture

**Alternative Pattern (Delegation):**
```java
public <T> T step(String name, Class<T> resultType, Supplier<T> func) {
    return stepAsync(name, resultType, func).join();  // Delegate to async
}

public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func) {
    // Single implementation
}
```

**Comparison with Other SDKs:**
- **TypeScript SDK**: Only has `step()` returning `DurablePromise<T>` (async)
- **Python SDK**: Only has `step()` returning `T` (sync)
- **Official Java SDK**: Only has `step()` returning `DurableTask<T>` (async-like)

**Pros of Delegation:**
- ✅ DRY - single implementation
- ✅ Guaranteed consistency between sync/async
- ✅ Easier to maintain

**Cons of Delegation:**
- ❌ Performance overhead (CompletableFuture creation for sync calls)
- ❌ Less explicit about what's happening
- ❌ Doesn't match other SDKs (they have only one method)

**Questions to Resolve:**
1. Should we keep both `step()` and `stepAsync()`, or just one?
2. If we keep both, should we use delegation pattern?
3. Should we align with official Java SDK and only have one method?

**Recommendation for Level 1 (Minimal):**
- Keep current approach (separate implementations)
- Consider refactoring in Level 2 based on user feedback

