# Java SDK Design Document - Level 1 (Minimal)

**Target Language:** Java 17  (More than 50% of Java Lambda Functions target Java11-17 - we should at least support Java 17)
**Conformance Level:** Level 1 (Minimal) - Core operations (STEP, WAIT) + basic error handling. Will be evolved after first design agreement.
**Version:** 1.0  
**Date:** December 8, 2025

## Overview

This document defines the minimal design for the Java SDK implementation of AWS Lambda Durable Functions. It focuses on Level 1 conformance: core STEP and WAIT operations with basic error handling and checkpoint/replay support.

## Project Configuration

- **Build Tool:** Maven
- **Java Version:** Java 17 (LTS)
- **Package:** `com.amazonaws.lambda.durable`
- **Dependencies:**
  - AWS SDK for Java v2 (Lambda client)
  - Jackson (JSON serialization)
  - JUnit 5 (testing)

## Core Design Decisions

### 1. Entry Point: Dual Pattern

**Design Decision:** Support both base class and wrapper patterns, sharing the same core logic.

**Option A - Base Class:**
```java
public abstract class DurableHandler<I, O> implements RequestHandler<DurableExecutionInput, DurableExecutionOutput> {
    @Override
    public final DurableExecutionOutput handleRequest(DurableExecutionInput input, Context context) {
        return DurableExecution.execute(input, context, this::handle);
    }
    
    protected abstract O handle(I input, DurableContext context);
}
```

**Option B - Wrapper:**
```java
public class DurableExecution {
    public static <I, O> RequestHandler<DurableExecutionInput, DurableExecutionOutput> wrap(
        BiFunction<I, DurableContext, O> handler
    ) {
        return (input, context) -> execute(input, context, handler);
    }
}
```

**Shared Core:**
```java
static <I, O> DurableExecutionOutput execute(
    DurableExecutionInput input, 
    Context lambdaContext,
    BiFunction<I, DurableContext, O> handler
) {
    // 1. Load checkpoint state
    // 2. Create DurableContext
    // 3. Extract user input
    // 4. Execute handler
    // 5. Return output envelope
}
```

**Rationale:** Both patterns use the same `execute()` method, avoiding duplication while providing flexibility.

### 1.1. Envelope Handling

**Design Decision:** Handle durable execution input/output envelope in the `execute()` method.

**Input Envelope:**
```java
record DurableExecutionInput(
    String durableExecutionArn,
    String checkpointToken,
    InitialExecutionState initialExecutionState
) {
    record InitialExecutionState(
        List<Operation> operations,
        String nextMarker
    ) {}
}
```

**Output Envelope:**
```java
record DurableExecutionOutput(
    String status,  // "SUCCEEDED", "FAILED", "PENDING"
    String result,  // Optional, JSON-serialized
    ErrorObject error  // Optional, for FAILED status
) {}
```

**Implementation:**
```java
static <I, O> DurableExecutionOutput execute(
    DurableExecutionInput input, 
    Context lambdaContext,
    BiFunction<I, DurableContext, O> handler
) {
    // 1. Load checkpoint state (with pagination if needed)
    var operations = loadAllOperations(input);
    
    // 2. Validate and extract EXECUTION operation (always first)
    if (operations.isEmpty() || operations.get(0).type() != OperationType.EXECUTION) {
        throw new IllegalStateException("First operation must be EXECUTION");
    }
    var executionOp = operations.get(0);
    
    // 3. Create ExecutionState with shared executor
    var state = new ExecutionState(
        input.durableExecutionArn(),
        input.checkpointToken(),
        operations,
        createClient(),
        DurableContext.executorService  // Pass shared executor
    );
    
    // 4. Create DurableContext with SerDes
    var serDes = new JacksonSerDes();
    var context = new DurableContext(state, serDes, lambdaContext);
    
    // 5. Extract original user input from EXECUTION operation
    var userInput = extractUserInput(executionOp, serDes);
    
    // 6. Execute handler
    try {
        var result = handler.apply(userInput, context);
        
        // 7. Return success envelope
        return DurableExecutionOutput.success(serializeResult(result));
    } catch (SuspendExecutionException e) {
        // 8. Wait operation suspended execution
        return DurableExecutionOutput.pending();
    } catch (Exception e) {
        // 9. Return failure envelope
        return DurableExecutionOutput.failure(ErrorObject.fromException(e));
    }
}

private static List<Operation> loadAllOperations(DurableExecutionInput input) {
    var operations = new ArrayList<>(input.initialExecutionState().operations());
    var nextMarker = input.initialExecutionState().nextMarker();
    
    // Paginate through all operations if needed
    while (nextMarker != null) {
        var response = client.getDurableExecutionState(
            input.durableExecutionArn(),
            nextMarker
        );
        operations.addAll(response.operations());
        nextMarker = response.nextMarker();
    }
    
    return operations;
}

private static <I> I extractUserInput(Operation executionOp, SerDes serDes) {
    var inputPayload = executionOp.executionDetails().inputPayload();
    return serDes.deserialize(inputPayload, inputType);
}
```

**Rationale:**
- SDK handles all envelope parsing and creation
- Supports pagination for large execution state
- Handles suspension via SuspendExecutionException

### 2. Async Model: CompletableFuture with Thread Pool

**Design Decision:** Use CompletableFuture for async operations with ExecutorService, providing synchronous convenience methods.

**Implementation:**
```java
public class DurableContext {
    
    private static final ExecutorService executorService = 
        Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2
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
    
    public <T> CompletableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func) {
        var operationId = nextOperationId();
        
        // Check replay (synchronous check)
        var existing = state.getOperation(operationId);
        if (existing.isPresent() && existing.get().status() == SUCCEEDED) {
            var result = serDes.deserialize(existing.get().result(), resultType);
            return CompletableFuture.completedFuture(result);
        }
        
        // Execute asynchronously in thread pool
        return CompletableFuture.supplyAsync(() -> {
            var result = func.get();
            checkpoint(operationId, result);
            return result;
        }, executorService);
    }
    
    public <T> T step(String name, Class<T> resultType, Supplier<T> func) {
        return stepAsync(name, resultType, func).join();
    }
    
    public void wait(Duration duration) {
        var operationId = nextOperationId();
        
        // Check if wait already completed (replay)
        var existing = state.getOperation(operationId);
        if (existing.isPresent() && existing.get().status() == SUCCEEDED) {
            return; // Wait already completed, continue
        }
        
        // Checkpoint wait and suspend execution
        checkpoint(operationId, duration);
        throw new SuspendExecutionException(); // Signal to return PENDING
    }
    
    public Context getLambdaContext() {
        return lambdaContext;
    }
    
    private String nextOperationId() {
        return String.valueOf(operationCounter.incrementAndGet());
    }
    
    private void checkpoint(String operationId, Object result) {
        var update = OperationUpdate.builder()
            .id(operationId)
            .action(OperationAction.SUCCEED)
            .payload(serDes.serialize(result))
            .build();
        
        state.checkpoint(update);
    }
}
```

**Usage:**
```java
// Synchronous
var order = context.step("process-order", Order.class, () -> processOrder(event));

// Async composition
var result = context.stepAsync("step1", String.class, () -> step1())
    .thenCompose(r1 -> context.stepAsync("step2", String.class, () -> step2(r1)))
    .join();

// Wait (always suspends)
context.wait(Duration.ofHours(1));
```

**Rationale:**
- Native Java async pattern
- Synchronous methods for simple cases
- Composable with standard library

### 3. DurableFuture Wrapper

**Design Decision:** Wrap CompletableFuture in DurableFuture for future extensibility.

**Implementation:**
```java
public class DurableFuture<T> implements Future<T> {
    private final CompletableFuture<T> delegate;
    
    DurableFuture(CompletableFuture<T> delegate) {
        this.delegate = delegate;
    }
    
    // Expose essential methods
    public T join() {
        return delegate.join();
    }
    
    public T get() throws InterruptedException, ExecutionException {
        return delegate.get();
    }
    
    public T get(long timeout, TimeUnit unit) 
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.get(timeout, unit);
    }
    
    // For Level 2+ composition
    public <U> DurableFuture<U> thenCompose(Function<T, DurableFuture<U>> fn) {
        return new DurableFuture<>(
            delegate.thenCompose(t -> fn.apply(t).delegate)
        );
    }
    
    // Package-private for internal use
    CompletableFuture<T> toCompletableFuture() {
        return delegate;
    }
}
```

**Rationale:**
- Provides foundation for Level 2+ features (map, parallel)
- Allows adding tracking/special behavior later without API changes
- Minimal overhead for Level 1
- Still composable like CompletableFuture

### 4. DurableContext Class

**Design Decision:** Concrete class for core durable operations (Level 1: STEP and WAIT only).

**Key Methods:**
```java
public class DurableContext {
    // Core operations
    public <T> T step(String name, Class<T> resultType, Supplier<T> func);
    public <T> DurableFuture<T> stepAsync(String name, Class<T> resultType, Supplier<T> func);
    
    public void wait(Duration duration);
    
    // Context access
    public Context getLambdaContext();
}
```

**Rationale:** 
- Single entry point for all operations
- Minimal surface area for Level 1
- No interface needed (only one implementation, matches TypeScript/Python SDKs)
- Still mockable with Mockito for testing

### 5. Execution State Management

**Design Decision:** Separate ExecutionState class manages checkpoint operations.

**Structure:**
```java
class ExecutionState {
    private final String durableExecutionArn;
    private final Map<String, Operation> checkpointLog;
    private final DurableExecutionClient client;
    private final AtomicReference<String> checkpointToken;
    private CheckpointBatcher batcher; 
    
    ExecutionState(String arn, String token, List<Operation> operations, 
                   DurableExecutionClient client, ExecutorService executor) {
        this.durableExecutionArn = arn;
        this.checkpointToken = new AtomicReference<>(token);
        this.checkpointLog = new ConcurrentHashMap<>();
        this.client = client;
        
        // Load initial operations
        operations.forEach(op -> checkpointLog.put(op.id(), op));
        
        this.batcher = new CheckpointBatcher(client, this, executor);
    }
    
    void checkpoint(OperationUpdate update) {
        batcher.add(update);
    }
    
    String getDurableExecutionArn() {
        return durableExecutionArn;
    }
    
    String getCheckpointToken() {
        return checkpointToken.get();
    }
    
    void updateCheckpointToken(String newToken) {
        checkpointToken.set(newToken);
    }
    
    void updateOperations(List<Operation> operations) {
        operations.forEach(op -> checkpointLog.put(op.id(), op));
    }
    
    Optional<Operation> getOperation(String operationId) {
        return Optional.ofNullable(checkpointLog.get(operationId));
    }
}
```

**Note:** Uses AWS SDK's `Operation` type directly (from `software.amazon.awssdk.services.lambda.model.Operation`).

**Rationale:**
- Thread-safe state management for async step processing
- Encapsulates checkpoint batching

### 6. Checkpoint Batching

**Design Decision:** Immediate processing with CompletableFuture tracking, matching TypeScript SDK's setImmediate pattern

**Key Design Principles:**
1. **CompletableFuture-based** - Returns `CompletableFuture<Void>` for sync/async support
2. **Immediate processing** - Uses executor to trigger processing immediately (not time-based)
3. **Opportunistic batching** - Operations arriving together batch automatically via thread scheduling
4. **750KB batch size limit** - Prevents API failures (matches TypeScript/Python SDKs)
5. **Single-threaded processing** - AtomicBoolean ensures only one batch processes at a time

**Implementation:**
```java
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
    
    void shutdown() {
        // Drain remaining items and fail them
        var remaining = new ArrayList<CheckpointRequest>();
        queue.drainTo(remaining);
        remaining.forEach(req -> 
            req.completion().completeExceptionally(new IllegalStateException("Batcher shutdown")));
    }
}
```

**ExecutionState Integration:**
```java
class ExecutionState {
    private final CheckpointBatcher batcher;
    
    // Synchronous checkpoint - waits for completion
    void checkpoint(OperationUpdate update) {
        var future = batcher.add(update);
        future.join();  // Block until done
    }
    
    // Asynchronous checkpoint - returns immediately
    void checkpointAsync(OperationUpdate update) {
        batcher.add(update);  // Don't wait
    }
}
```

**Rationale:**
- **Matches official AWS Java SDK pattern** - CompletableFuture-based async execution
- **Adds 750KB limit** - Prevents API failures (official SDK has TODO for this)
- **Immediate processing** - Matches TypeScript SDK's setImmediate pattern for Level 1
- **Opportunistic batching** - Operations arriving together batch automatically via thread scheduling (~1-5ms delay)
- **Sync/async support** - `checkpoint()` blocks, `checkpointAsync()` returns immediately

### 7. Configuration: No Config for Level 1

**Design Decision:** No configuration objects for minimal SDK.

**Implementation:**
```java
// Simple API - no config parameters
context.step("process", String.class, () -> processData());
context.wait(Duration.ofMinutes(5));
```

**Rationale:**
- Minimal complexity
- Configuration (retry strategies, semantics, etc.) added in Level 2

### 8. Serialization: SerDes Interface

**Design Decision:** Generic SerDes interface with Jackson default.

**Interface:**
```java
public interface SerDes {
    String serialize(Object value);
    <T> T deserialize(String data, Class<T> type);
}
```

**Default Implementation:**
```java
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
        } catch (JsonProcessingException e) {
            throw new SerializationException("Failed to serialize", e);
        }
    }
    
    @Override
    public <T> T deserialize(String data, Class<T> type) {
        if (data == null) return null;
        
        try {
            return mapper.readValue(data, type);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Failed to deserialize", e);
        }
    }
}
```

**Rationale:**
- Pluggable serialization
- Null-safe handling
- Forward compatibility (FAIL_ON_UNKNOWN_PROPERTIES disabled)
- ISO-8601 date format

### 9. Error Handling: Exception Hierarchy

**Design Decision:** Structured exception hierarchy with cause chaining.

**Hierarchy:**
```java
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

public class StepException extends DurableExecutionException {
    public StepException(String message) {
        super(message, "StepException");
    }
    
    public StepException(String message, Throwable cause) {
        super(message, "StepException", cause);
    }
}

public class SuspendExecutionException extends DurableExecutionException {
    public SuspendExecutionException() {
        super("Execution suspended for wait operation", "SuspendExecution");
    }
}
```

**Note:** `SuspendExecutionException` is used for control flow, not error reporting. It signals that a wait operation has suspended execution and the handler should return PENDING status.

**Error Serialization:**
```java
record ErrorObject(
    String errorType,
    String errorMessage,
    List<String> stackTrace  // Optional
) {
    public static ErrorObject fromException(Throwable e) {
        return new ErrorObject(
            e.getClass().getSimpleName(),
            e.getMessage(),
            Arrays.stream(e.getStackTrace())
                .map(StackTraceElement::toString)
                .limit(50)  // Limit to avoid excessive size
                .toList()
        );
    }
}
```

**Rationale:**
- Cause chaining for debugging
- Serializable for checkpoints

### 10. Operation ID Generation

**Design Decision:** Sequential counter (like TypeScript SDK). Todo: Might need parent naming for Childs in the future (Level 2).

**Implementation:**
```java
public class DurableContext {
    private final AtomicInteger operationCounter;

    private String nextOperationId() {
        return String.valueOf(operationCounter.incrementAndGet());
    }
}
```

**Rationale:**
- Matches TypeScript implementation
- Name is stored separately as metadata

## Critical Implementation Details

### 1. Thread Safety

All shared state must be thread-safe since steps can be processed async:
- Use ConcurrentHashMap for checkpoint log
- Use AtomicReference for checkpoint token

### 2. Resource Management

**Design Decision:** Thread pool and resources are reused across invocations for better performance.

```java
public class DurableContext {
    private final ExecutorService executor;
    private final ExecutionState state;
    
    // No close() method needed
    // ExecutorService reused across invocations
    // Lambda handles cleanup on container termination
}
```

**Rationale:**
- Matches TypeScript/Python SDK behavior
- Better warm start performance
- No need to recreate thread pool per invocation
- Contexts are not shared 

### 3. Determinism Validation

Validate operation sequence during replay:
```java
void validateReplay(String operationId, OperationType type, Operation checkpoint) {
    if (checkpoint.type() != type) {
        throw new NonDeterministicException(
            "Operation type mismatch: expected " + type + ", got " + checkpoint.type()
        );
    }
}
```

## Testing Strategy (Generated early draft - not final)

### 1. Unit Tests
- Test handlers independently
- Mock ExecutionState
- Test serialization

### 2. Integration Tests
- Test with real Lambda environment
- Test checkpoint and replay
- Test error handling

### 3. Local Testing Infrastructure

**Design Decision:** Provide in-memory checkpoint storage for local testing without Lambda API.

**Implementation:**
```java
public class InMemoryCheckpointStorage implements DurableExecutionClient {
    private final Map<String, Operation> operations = new ConcurrentHashMap<>();
    private final AtomicReference<String> checkpointToken = new AtomicReference<>(UUID.randomUUID().toString());
    
    @Override
    public CheckpointResponse checkpoint(List<OperationUpdate> updates) {
        // Apply updates to in-memory storage
        updates.forEach(this::applyUpdate);
        
        // Generate new token
        var newToken = UUID.randomUUID().toString();
        checkpointToken.set(newToken);
        
        return CheckpointResponse.builder()
            .checkpointToken(newToken)
            .newExecutionState(ExecutionState.builder()
                .operations(new ArrayList<>(operations.values()))
                .build())
            .build();
    }
    
    @Override
    public GetExecutionStateResponse getExecutionState(String executionArn, String nextMarker) {
        return GetExecutionStateResponse.builder()
            .operations(new ArrayList<>(operations.values()))
            .build();
    }
    
    private void applyUpdate(OperationUpdate update) {
        var operation = toOperation(update);
        operations.put(update.id(), operation);
    }
    
    private Operation toOperation(OperationUpdate update) {
        return Operation.builder()
            .id(update.id())
            .type(update.type())
            .status(deriveStatus(update.action()))
            .name(update.name())
            // ... map other fields
            .build();
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

**Test Runner:**
```java
public class LocalDurableTestRunner<I, O> {
    private final BiFunction<I, DurableContext, O> handler;
    private final InMemoryCheckpointStorage storage;
    
    public LocalDurableTestRunner(BiFunction<I, DurableContext, O> handler) {
        this.handler = handler;
        this.storage = new InMemoryCheckpointStorage();
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
    
    private DurableExecutionInput createDurableInput(I input) {
        return DurableExecutionInput.builder()
            .durableExecutionArn("test-execution-" + UUID.randomUUID())
            .checkpointToken(storage.getCheckpointToken())
            .initialExecutionState(ExecutionState.builder()
                .operations(storage.getOperations())
                .build())
            .build();
    }
}
```

**Usage:**
```java
@Test
void testDurableFunction() {
    var runner = new LocalDurableTestRunner<Event, Result>(
        (event, context) -> {
            var step1 = context.step("step1", String.class, () -> "result1");
            var step2 = context.step("step2", String.class, () -> "result2");
            return new Result(step1, step2);
        }
    );
    
    var result = runner.run(new Event("test"));
    
    assertEquals("result1", result.getResult().step1);
    assertEquals(2, result.getOperations().size());
}
```

**Rationale:**
- Enables local testing without Lambda infrastructure
- Matches TypeScript/Python SDK approach

### 4. Test Utilities

Additional test utilities for common scenarios:
```java
public class DurableTestUtils {
    public static Context mockLambdaContext() {
        // Create mock Lambda context
    }
    
    public static void assertOperationExists(List<Operation> operations, String name) {
        // Assert operation with name exists
    }
}
```

## Next Steps

1. **Project Setup**
   - Maven configuration
   - Package structure
   - Dependencies (AWS SDK, Jackson)

2. **Core Implementation**
   - DurableContext interface
   - ExecutionState class
   - CheckpointBatcher
   - Step handler
   - Wait handler

3. **Testing**
   - Unit test framework
   - Integration test utilities
   - Example functions

4. **Documentation**
   - API reference
   - User guide

## Conclusion

The Java SDK Level 1 (Minimal) will provide:
- Type-safe, idiomatic Java API
- CompletableFuture-based async model
- Core STEP and WAIT operations
- Basic error handling
