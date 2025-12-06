# Implementation Strategy - Java 17 PoC

**Version:** 2.0  
**Date:** December 6, 2025  
**Status:** Active Design - PoC Ready

## Overview

This document provides implementation guidance for building the Java SDK PoC. This is a **fresh implementation**, not a refactor.

**Goals:**
- Java 17 LTS compatible
- ~500 lines of core code
- Idiomatic Java patterns
- Functional PoC in 3 weeks

## 1. Architecture

```
┌─────────────────────────────────────┐
│      User Handler Code              │
│  extends DurableHandler<I,O>        │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│      DurableHandler<I,O>            │
│  - Parse envelope                   │
│  - Create DurableContext            │
│  - Call user handleRequest()        │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│      DurableContextImpl             │
│  - step() / stepAsync()             │
│  - wait() / timer()                 │
│  - Replay logic                     │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│      ExecutionState                 │
│  - Checkpoint batching              │
│  - Active task tracking             │
│  - Suspension detection             │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│  Lambda Durable Execution API       │
│  - checkpointDurableExecution()     │
│  - suspendDurableExecution()        │
│  - completeDurableExecution()       │
└─────────────────────────────────────┘
```

## 2. Core Components

### 2.1 DurableContextImpl

**Responsibilities:**
- Implement DurableContext interface
- Replay logic (check checkpoint log before execution)
- Delegate to ExecutionState for checkpointing
- Manage thread pool for async operations

**Key Patterns:**
- CompletableFuture for async operations
- Callable<T> for step actions
- Semaphore for active task tracking

```java
public class DurableContextImpl implements DurableContext {
    private final ExecutionState state;
    private final ExecutorService executor;
    private final Semaphore activeTasks;
    private int operationCounter = 0;
    
    public <T> T step(String name, Class<T> type, Callable<T> action) {
        return stepAsync(name, type, action).get();
    }
    
    public <T> DurableFuture<T> stepAsync(String name, Class<T> type, Callable<T> action) {
        String opId = String.valueOf(operationCounter++);
        
        // Check replay
        Operation existing = state.getOperation(opId);
        if (existing != null && existing.isSucceeded()) {
            T result = state.deserialize(existing.getResult(), type);
            return DurableFuture.completed(result);
        }
        
        // Execute
        CompletableFuture<T> future = new CompletableFuture<>();
        activeTasks.release(); // Increment active count
        
        executor.execute(() -> {
            try {
                T result = action.call();
                state.checkpoint(opId, name, result);
                future.complete(result);
            } catch (Exception e) {
                state.checkpointFailure(opId, name, e);
                future.completeExceptionally(e);
            } finally {
                activeTasks.acquire(); // Decrement active count
                state.checkSuspension();
            }
        });
        
        return new DurableFutureImpl<>(future);
    }
    
    public void wait(Duration duration) {
        timer(duration).get();
    }
    
    public DurableFuture<Void> timer(Duration duration) {
        String opId = String.valueOf(operationCounter++);
        
        // Check replay
        Operation existing = state.getOperation(opId);
        if (existing != null && existing.isSucceeded()) {
            return DurableFuture.completed(null);
        }
        
        // Register wait with service
        state.checkpointWait(opId, duration);
        
        // Service will complete this when timer expires
        CompletableFuture<Void> future = state.registerWaitCompletion(opId);
        return new DurableFutureImpl<>(future);
    }
}
```

### 2.2 ExecutionState

**Responsibilities:**
- Maintain checkpoint log (operation history)
- Batch checkpoint updates (100ms window)
- Track active tasks (Semaphore)
- Detect suspension (when activeTasks = 0)
- Call Lambda Durable Execution API

**Key Patterns:**
- BlockingQueue for batching
- ScheduledExecutorService for periodic flush
- ConcurrentHashMap for operation log
- Semaphore for task counting

```java
public class ExecutionState {
    private final LambdaClient lambdaClient;
    private final SerDes serDes;
    private final String durableExecutionArn;
    private String checkpointToken;
    
    // Checkpoint log
    private final Map<String, Operation> operations = new ConcurrentHashMap<>();
    
    // Checkpoint batching
    private final BlockingQueue<OperationUpdate> pendingUpdates = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService batcher;
    
    // Active task tracking
    private final Semaphore activeTasks;
    
    public ExecutionState(LambdaClient client, SerDes serDes, 
                         String arn, String token, Semaphore activeTasks) {
        this.lambdaClient = client;
        this.serDes = serDes;
        this.durableExecutionArn = arn;
        this.checkpointToken = token;
        this.activeTasks = activeTasks;
        
        // Start background batcher
        this.batcher = Executors.newSingleThreadScheduledExecutor();
        this.batcher.scheduleAtFixedRate(
            this::flushCheckpoints,
            100, 100, TimeUnit.MILLISECONDS
        );
    }
    
    public void checkpoint(String id, String name, Object result) {
        OperationUpdate update = OperationUpdate.builder()
            .id(id)
            .name(name)
            .type(OperationType.STEP)
            .action(OperationAction.SUCCEED)
            .payload(serDes.serialize(result))
            .build();
        
        pendingUpdates.offer(update);
    }
    
    private void flushCheckpoints() {
        List<OperationUpdate> batch = new ArrayList<>();
        pendingUpdates.drainTo(batch, 100); // Max 100 per batch
        
        if (batch.isEmpty()) {
            return;
        }
        
        try {
            CheckpointDurableExecutionResponse response = 
                lambdaClient.checkpointDurableExecution(
                    CheckpointDurableExecutionRequest.builder()
                        .checkpointToken(checkpointToken)
                        .durableExecutionArn(durableExecutionArn)
                        .updates(batch)
                        .build()
                );
            
            // Update token and operations
            checkpointToken = response.checkpointToken();
            updateOperations(response.newExecutionState().operations());
            
        } catch (Exception e) {
            // Re-queue for retry
            pendingUpdates.addAll(batch);
            throw new CheckpointException("Checkpoint failed", e);
        }
    }
    
    public void checkSuspension() {
        if (activeTasks.availablePermits() == 0) {
            // Flush any pending checkpoints
            flushCheckpoints();
            
            // Suspend execution
            lambdaClient.suspendDurableExecution(
                SuspendDurableExecutionRequest.builder()
                    .checkpointToken(checkpointToken)
                    .durableExecutionArn(durableExecutionArn)
                    .build()
            );
        }
    }
}
```

### 2.3 DurableFutureImpl

**Responsibilities:**
- Wrap CompletableFuture with DurableFuture interface
- Provide replay-safe semantics
- Support map/flatMap composition

```java
public class DurableFutureImpl<T> implements DurableFuture<T> {
    private final CompletableFuture<T> future;
    
    public DurableFutureImpl(CompletableFuture<T> future) {
        this.future = future;
    }
    
    @Override
    public T get() {
        return future.join();
    }
    
    @Override
    public boolean isDone() {
        return future.isDone();
    }
    
    @Override
    public <R> DurableFuture<R> map(Function<T, R> mapper) {
        return new DurableFutureImpl<>(future.thenApply(mapper));
    }
    
    @Override
    public <R> DurableFuture<R> flatMap(Function<T, DurableFuture<R>> mapper) {
        CompletableFuture<R> result = future.thenCompose(value -> {
            DurableFuture<R> next = mapper.apply(value);
            return ((DurableFutureImpl<R>) next).future;
        });
        return new DurableFutureImpl<>(result);
    }
    
    public static <T> DurableFuture<T> completed(T value) {
        return new DurableFutureImpl<>(CompletableFuture.completedFuture(value));
    }
}
```

### 2.4 DurableHandler

**Responsibilities:**
- Parse durable execution envelope
- Create DurableContext
- Deserialize input
- Call user's handleRequest()
- Handle suspension/completion
- Serialize output

```java
public abstract class DurableHandler<I, O> implements RequestStreamHandler {
    private final SerDes serDes;
    
    protected DurableHandler() {
        this(new JacksonSerDes());
    }
    
    protected DurableHandler(SerDes serDes) {
        this.serDes = serDes;
    }
    
    @Override
    public final void handleRequest(InputStream inputStream, 
                                    OutputStream outputStream, 
                                    Context lambdaContext) throws IOException {
        // 1. Parse envelope
        DurableExecutionInput envelope = parseEnvelope(inputStream);
        
        // 2. Create ExecutionState
        Semaphore activeTasks = new Semaphore(0);
        ExecutionState state = new ExecutionState(
            createLambdaClient(),
            serDes,
            envelope.getDurableExecutionArn(),
            envelope.getCheckpointToken(),
            activeTasks
        );
        
        // Load initial operations
        state.loadOperations(envelope.getInitialExecutionState().getOperations());
        
        // 3. Create DurableContext
        ExecutorService executor = Executors.newFixedThreadPool(10);
        DurableContext durableContext = new DurableContextImpl(
            state,
            executor,
            activeTasks,
            lambdaContext
        );
        
        // 4. Deserialize input
        I input = deserializeInput(envelope);
        
        // 5. Call user handler
        O output = handleRequest(input, durableContext);
        
        // 6. Complete execution
        state.complete(output);
        
        // 7. Serialize response
        serializeOutput(output, outputStream);
        
        // 8. Cleanup
        executor.shutdown();
        state.shutdown();
    }
    
    public abstract O handleRequest(I input, DurableContext context);
    
    private I deserializeInput(DurableExecutionInput envelope) {
        Class<I> inputType = getInputType();
        String payload = envelope.getInitialExecutionState()
            .getOperations().get(0)
            .getExecutionDetails()
            .getInputPayload();
        return serDes.deserialize(payload, inputType);
    }
    
    @SuppressWarnings("unchecked")
    private Class<I> getInputType() {
        Type superclass = getClass().getGenericSuperclass();
        ParameterizedType parameterized = (ParameterizedType) superclass;
        return (Class<I>) parameterized.getActualTypeArguments()[0];
    }
}
```

## 3. Key Implementation Patterns

### 3.1 Replay Detection

```java
// Before executing, check if operation already completed
Operation existing = state.getOperation(opId);
if (existing != null && existing.isSucceeded()) {
    // Return cached result, don't re-execute
    return deserialize(existing.getResult(), type);
}

// Otherwise, execute normally
```

### 3.2 Checkpoint Batching

```java
// Non-blocking queue operation
pendingUpdates.offer(update);

// Background thread flushes periodically
batcher.scheduleAtFixedRate(() -> {
    List<OperationUpdate> batch = new ArrayList<>();
    pendingUpdates.drainTo(batch, 100);
    
    if (!batch.isEmpty()) {
        lambdaClient.checkpointDurableExecution(batch);
    }
}, 100, 100, TimeUnit.MILLISECONDS);
```

### 3.3 Suspension Detection

```java
// Increment when starting task
activeTasks.release();

// Decrement when completing task
activeTasks.acquire();

// Check if should suspend
if (activeTasks.availablePermits() == 0) {
    suspendExecution();
}
```

### 3.4 Retry with Exponential Backoff

```java
public <T> T executeWithRetry(Callable<T> action, RetryPolicy policy) {
    int attempt = 0;
    Exception lastException = null;
    
    while (attempt < policy.getMaxAttempts()) {
        try {
            return action.call();
        } catch (Exception e) {
            lastException = e;
            attempt++;
            
            if (attempt < policy.getMaxAttempts()) {
                long delay = calculateDelay(attempt, policy);
                Thread.sleep(delay);
            }
        }
    }
    
    throw new StepFailedException("Step failed", attempt, lastException);
}

private long calculateDelay(int attempt, RetryPolicy policy) {
    long delay = (long) (policy.getInitialInterval().toMillis() * 
                        Math.pow(policy.getBackoffCoefficient(), attempt - 1));
    return Math.min(delay, policy.getMaxInterval().toMillis());
}
```

## 4. Implementation Phases

### Phase 1: Core Classes (Week 1)

**Day 1-2: Interfaces and Models**
- [ ] DurableContext interface
- [ ] DurableFuture interface
- [ ] SerDes interface + JacksonSerDes
- [ ] Configuration classes (StepConfig, RetryPolicy)
- [ ] Exception classes

**Day 3-4: Handler Registration**
- [ ] DurableHandler base class
- [ ] DurableExecution wrapper
- [ ] Envelope parsing
- [ ] Input/output serialization

**Day 5: Testing Setup**
- [ ] Unit test framework
- [ ] Mock utilities
- [ ] Test fixtures

### Phase 2: Execution Logic (Week 2)

**Day 1-2: DurableContextImpl**
- [ ] step() implementation with replay
- [ ] stepAsync() implementation
- [ ] Operation ID generation
- [ ] Thread pool management

**Day 3-4: ExecutionState**
- [ ] Checkpoint log management
- [ ] Checkpoint batching
- [ ] Active task tracking
- [ ] Suspension detection

**Day 5: Wait Operations**
- [ ] wait() implementation
- [ ] timer() implementation
- [ ] Service-side completion

### Phase 3: Polish & Testing (Week 3)

**Day 1-2: Retry Logic**
- [ ] RetryPolicy implementation
- [ ] Exponential backoff
- [ ] Error handling

**Day 3-4: Integration Tests**
- [ ] End-to-end workflow tests
- [ ] Replay scenarios
- [ ] Suspension/resumption tests

**Day 5: Documentation & Examples**
- [ ] API documentation
- [ ] Usage examples
- [ ] README

## 5. Testing Strategy

### 5.1 Unit Tests

```java
@Test
void testStepReplay() {
    // Setup mock state with existing operation
    ExecutionState mockState = mock(ExecutionState.class);
    Operation existing = Operation.builder()
        .id("0")
        .status(OperationStatus.SUCCEEDED)
        .result("{\"value\":\"cached\"}")
        .build();
    when(mockState.getOperation("0")).thenReturn(existing);
    
    // Create context
    DurableContext ctx = new DurableContextImpl(mockState, ...);
    
    // Execute step
    String result = ctx.step("test", String.class, () -> "fresh");
    
    // Should return cached result, not execute action
    assertEquals("cached", result);
    verify(mockState, never()).checkpoint(any(), any(), any());
}
```

### 5.2 Integration Tests

```java
@Test
void testEndToEndWorkflow() {
    // Create test handler
    TestHandler handler = new TestHandler();
    
    // Create mock Lambda context
    Context lambdaContext = mock(Context.class);
    
    // Create envelope with initial state
    String envelope = createTestEnvelope();
    InputStream input = new ByteArrayInputStream(envelope.getBytes());
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    
    // Execute
    handler.handleRequest(input, output, lambdaContext);
    
    // Verify output
    String result = output.toString();
    assertTrue(result.contains("expected-result"));
}
```

## 6. Dependencies

### 6.1 Required

```xml
<dependencies>
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
</dependencies>
```

### 6.2 Testing

```xml
<dependencies>
    <!-- JUnit 5 -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.0</version>
        <scope>test</scope>
    </dependency>
    
    <!-- Mockito -->
    <dependency>
        <groupId>org.mockito</groupId>
        <artifactId>mockito-core</artifactId>
        <version>5.5.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## 7. Code Metrics Target

| Metric | Target | Rationale |
|--------|--------|-----------|
| **Core LOC** | ~500 | Simple, maintainable |
| **Cyclomatic Complexity** | < 10 per method | Easy to understand |
| **Test Coverage** | > 80% | High confidence |
| **Public API Classes** | ~10 | Focused surface area |
| **Internal Classes** | ~5 | Minimal complexity |

## 8. Success Criteria

✅ **Functional**
- Step execution with replay works
- Wait operations suspend/resume correctly
- Checkpoint batching reduces API calls
- Retry with exponential backoff works

✅ **Quality**
- All unit tests pass
- Integration tests pass
- Code coverage > 80%
- No critical bugs

✅ **Usability**
- Simple handler examples work
- API is intuitive
- Error messages are clear
- Documentation is complete

## 9. Next Steps

1. **Set up project structure** - Maven/Gradle, package layout
2. **Implement Phase 1** - Core interfaces and models
3. **Implement Phase 2** - Execution logic
4. **Implement Phase 3** - Testing and polish
5. **Create examples** - Sample handlers
6. **Write documentation** - README, API docs

**Timeline:** 3 weeks to functional PoC

---

**Status:** Ready for Implementation  
**Target:** Java 17 LTS, ~500 LOC  
**Last Updated:** December 6, 2025
