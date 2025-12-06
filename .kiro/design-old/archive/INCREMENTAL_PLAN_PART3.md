# Incremental Plan - Part 3: Retry, Batching & Handler

**Continuing from Increment 14...**

---

## 🎯 Increment 15: Simple Retry Policy (30 min)

**Goal:** Add basic retry configuration.

### Step 15.1: Create RetryPolicy class
```java
package com.amazonaws.lambda.durable.config;

import java.time.Duration;

/**
 * Simple retry policy.
 * Start with just max attempts.
 */
public class RetryPolicy {
    private final int maxAttempts;
    
    public RetryPolicy(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
    
    public int getMaxAttempts() {
        return maxAttempts;
    }
    
    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(3);
    }
}
```

### Step 15.2: Test RetryPolicy
```java
package com.amazonaws.lambda.durable.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {
    
    @Test
    void testDefaultPolicy() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();
        assertEquals(3, policy.getMaxAttempts());
    }
    
    @Test
    void testCustomPolicy() {
        RetryPolicy policy = new RetryPolicy(5);
        assertEquals(5, policy.getMaxAttempts());
    }
}
```

### ✅ Verify
```bash
mvn test
```

**What you learned:** Simple configuration objects.

---

## 🎯 Increment 16: Add Retry to step() (45 min)

**Goal:** Retry failed steps.

### Step 16.1: Add retry logic to DurableContextImpl
```java
@Override
public <T> T step(String name, Class<T> type, Callable<T> action) {
    return step(name, type, action, RetryPolicy.defaultPolicy());
}

// New overload with retry
public <T> T step(String name, Class<T> type, Callable<T> action, RetryPolicy policy) {
    String opId = String.valueOf(operationCounter++);
    
    // Check replay
    Operation existing = checkpointLog.get(opId);
    if (existing != null) {
        System.out.println("REPLAY: " + name + " (id=" + opId + ")");
        return serDes.deserialize(existing.getResult(), type);
    }
    
    // Execute with retry
    Exception lastException = null;
    for (int attempt = 1; attempt <= policy.getMaxAttempts(); attempt++) {
        try {
            System.out.println("Executing: " + name + " (id=" + opId + ", attempt=" + attempt + ")");
            T result = action.call();
            
            // Checkpoint success
            String resultJson = serDes.serialize(result);
            checkpointLog.put(opId, new Operation(opId, name, resultJson));
            
            System.out.println("Completed: " + name);
            return result;
            
        } catch (Exception e) {
            lastException = e;
            System.out.println("Attempt " + attempt + " failed: " + e.getMessage());
            
            if (attempt < policy.getMaxAttempts()) {
                // Simple 1 second delay between retries
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    
    throw new RuntimeException("Step failed after " + policy.getMaxAttempts() + " attempts", lastException);
}
```

### Step 16.2: Test retry
```java
@Test
void testRetrySuccess() {
    DurableContextImpl ctx = new DurableContextImpl();
    AtomicInteger attempts = new AtomicInteger(0);
    
    // Fails twice, succeeds on third attempt
    String result = ctx.step("retry-test", String.class, () -> {
        int attempt = attempts.incrementAndGet();
        if (attempt < 3) {
            throw new RuntimeException("Attempt " + attempt + " failed");
        }
        return "success";
    }, new RetryPolicy(3));
    
    assertEquals("success", result);
    assertEquals(3, attempts.get());
}

@Test
void testRetryFailure() {
    DurableContextImpl ctx = new DurableContextImpl();
    
    // Always fails
    assertThrows(RuntimeException.class, () -> {
        ctx.step("always-fails", String.class, () -> {
            throw new RuntimeException("Always fails");
        }, new RetryPolicy(3));
    });
}
```

### ✅ Verify
```bash
mvn test
# Check console for retry attempts
```

**What you learned:**
- Retry loop pattern
- Exception handling
- Configurable behavior

---

## 🎯 Increment 17: Exponential Backoff (30 min)

**Goal:** Add smarter retry delays.

### Step 17.1: Enhance RetryPolicy
```java
public class RetryPolicy {
    private final int maxAttempts;
    private final Duration initialInterval;
    private final double backoffCoefficient;
    
    public RetryPolicy(int maxAttempts, Duration initialInterval, double backoffCoefficient) {
        this.maxAttempts = maxAttempts;
        this.initialInterval = initialInterval;
        this.backoffCoefficient = backoffCoefficient;
    }
    
    public int getMaxAttempts() { return maxAttempts; }
    public Duration getInitialInterval() { return initialInterval; }
    public double getBackoffCoefficient() { return backoffCoefficient; }
    
    public Duration calculateDelay(int attempt) {
        long delayMs = (long) (initialInterval.toMillis() * 
                              Math.pow(backoffCoefficient, attempt - 1));
        return Duration.ofMillis(delayMs);
    }
    
    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(3, Duration.ofSeconds(1), 2.0);
    }
}
```

### Step 17.2: Use exponential backoff in step()
```java
// In retry loop, replace fixed delay with:
if (attempt < policy.getMaxAttempts()) {
    Duration delay = policy.calculateDelay(attempt);
    System.out.println("Waiting " + delay + " before retry");
    try {
        Thread.sleep(delay.toMillis());
    } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
    }
}
```

### Step 17.3: Test exponential backoff
```java
@Test
void testExponentialBackoff() {
    RetryPolicy policy = new RetryPolicy(4, Duration.ofMillis(100), 2.0);
    
    assertEquals(Duration.ofMillis(100), policy.calculateDelay(1));  // 100ms
    assertEquals(Duration.ofMillis(200), policy.calculateDelay(2));  // 200ms
    assertEquals(Duration.ofMillis(400), policy.calculateDelay(3));  // 400ms
    assertEquals(Duration.ofMillis(800), policy.calculateDelay(4));  // 800ms
}
```

### ✅ Verify
```bash
mvn test
```

**What you learned:**
- Exponential backoff algorithm
- Duration calculations
- Retry strategies

---

## 🎯 Increment 18: Separate ExecutionState (1 hour)

**Goal:** Extract checkpoint management to separate class.

### Step 18.1: Create ExecutionState class
```java
package com.amazonaws.lambda.durable.internal;

import com.amazonaws.lambda.durable.internal.model.Operation;
import com.amazonaws.lambda.durable.serde.SerDes;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages checkpoint log.
 * Start simple - just in-memory storage.
 */
public class ExecutionState {
    
    private final Map<String, Operation> checkpointLog = new HashMap<>();
    private final SerDes serDes;
    
    public ExecutionState(SerDes serDes) {
        this.serDes = serDes;
    }
    
    public Operation getOperation(String id) {
        return checkpointLog.get(id);
    }
    
    public void checkpoint(String id, String name, Object result) {
        String resultJson = serDes.serialize(result);
        checkpointLog.put(id, new Operation(id, name, resultJson));
        System.out.println("Checkpointed: " + name + " (id=" + id + ")");
    }
    
    public <T> T deserialize(String data, Class<T> type) {
        return serDes.deserialize(data, type);
    }
    
    public Map<String, Operation> getCheckpointLog() {
        return checkpointLog;
    }
}
```

### Step 18.2: Update DurableContextImpl to use ExecutionState
```java
public class DurableContextImpl implements DurableContext {
    
    private int operationCounter = 0;
    private final ExecutionState state;  // Replace checkpointLog and serDes
    private final ExecutorService executor;
    
    public DurableContextImpl() {
        this(new ExecutionState(new JacksonSerDes()), Executors.newFixedThreadPool(5));
    }
    
    public DurableContextImpl(ExecutionState state, ExecutorService executor) {
        this.state = state;
        this.executor = executor;
    }
    
    @Override
    public <T> T step(String name, Class<T> type, Callable<T> action, RetryPolicy policy) {
        String opId = String.valueOf(operationCounter++);
        
        // Check replay
        Operation existing = state.getOperation(opId);
        if (existing != null) {
            System.out.println("REPLAY: " + name + " (id=" + opId + ")");
            return state.deserialize(existing.getResult(), type);
        }
        
        // Execute with retry
        Exception lastException = null;
        for (int attempt = 1; attempt <= policy.getMaxAttempts(); attempt++) {
            try {
                System.out.println("Executing: " + name + " (id=" + opId + ", attempt=" + attempt + ")");
                T result = action.call();
                
                state.checkpoint(opId, name, result);  // Use state
                
                System.out.println("Completed: " + name);
                return result;
                
            } catch (Exception e) {
                lastException = e;
                System.out.println("Attempt " + attempt + " failed: " + e.getMessage());
                
                if (attempt < policy.getMaxAttempts()) {
                    Duration delay = policy.calculateDelay(attempt);
                    try {
                        Thread.sleep(delay.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        throw new RuntimeException("Step failed after retries", lastException);
    }
    
    // Update other methods similarly...
    
    public ExecutionState getState() {
        return state;
    }
}
```

### Step 18.3: Update tests
```java
@Test
void testWithExecutionState() {
    ExecutionState state = new ExecutionState(new JacksonSerDes());
    DurableContextImpl ctx = new DurableContextImpl(state, Executors.newFixedThreadPool(5));
    
    String result = ctx.step("test", String.class, () -> "value");
    
    assertEquals("value", result);
    assertEquals(1, state.getCheckpointLog().size());
}
```

### ✅ Verify
```bash
mvn test
```

**What you learned:**
- Separation of concerns
- Dependency injection
- Refactoring

---

## 🎯 Increment 19: Add Checkpoint Batching (1 hour)

**Goal:** Batch checkpoint operations.

### Step 19.1: Add batching to ExecutionState
```java
public class ExecutionState {
    
    private final Map<String, Operation> checkpointLog = new HashMap<>();
    private final SerDes serDes;
    private final BlockingQueue<Operation> pendingCheckpoints = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService batcher;
    
    public ExecutionState(SerDes serDes) {
        this.serDes = serDes;
        
        // Start background batcher
        this.batcher = Executors.newSingleThreadScheduledExecutor();
        this.batcher.scheduleAtFixedRate(
            this::flushCheckpoints,
            100, 100, TimeUnit.MILLISECONDS
        );
    }
    
    public Operation getOperation(String id) {
        return checkpointLog.get(id);
    }
    
    public void checkpoint(String id, String name, Object result) {
        String resultJson = serDes.serialize(result);
        Operation op = new Operation(id, name, resultJson);
        
        // Queue for batching (non-blocking)
        pendingCheckpoints.offer(op);
        System.out.println("Queued checkpoint: " + name + " (id=" + id + ")");
    }
    
    private void flushCheckpoints() {
        List<Operation> batch = new ArrayList<>();
        pendingCheckpoints.drainTo(batch, 100);  // Max 100 per batch
        
        if (batch.isEmpty()) {
            return;
        }
        
        System.out.println("Flushing " + batch.size() + " checkpoints");
        
        // Add to checkpoint log
        for (Operation op : batch) {
            checkpointLog.put(op.getId(), op);
        }
        
        // In real implementation, would call Lambda API here
    }
    
    public void shutdown() {
        flushCheckpoints();  // Flush remaining
        batcher.shutdown();
    }
    
    // ... other methods ...
}
```

### Step 19.2: Test batching
```java
@Test
void testCheckpointBatching() throws Exception {
    ExecutionState state = new ExecutionState(new JacksonSerDes());
    DurableContextImpl ctx = new DurableContextImpl(state, Executors.newFixedThreadPool(5));
    
    // Execute many steps quickly
    for (int i = 0; i < 10; i++) {
        final int index = i;
        ctx.step("step-" + i, Integer.class, () -> index);
    }
    
    // Wait for batch to flush
    Thread.sleep(200);
    
    // All should be checkpointed
    assertEquals(10, state.getCheckpointLog().size());
    
    state.shutdown();
}
```

### ✅ Verify
```bash
mvn test
# Check console - should see "Flushing N checkpoints"
```

**What you learned:**
- Checkpoint batching
- BlockingQueue
- ScheduledExecutorService
- Background threads

---

## 🎯 Increment 20: Simple Handler Base Class (45 min)

**Goal:** Create base class for Lambda handlers.

### Step 20.1: Add Lambda dependency
```xml
<dependency>
    <groupId>com.amazonaws</groupId>
    <artifactId>aws-lambda-java-core</artifactId>
    <version>1.2.3</version>
</dependency>
```

### Step 20.2: Create DurableHandler
```java
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.internal.DurableContextImpl;
import com.amazonaws.lambda.durable.internal.ExecutionState;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.concurrent.Executors;

/**
 * Base class for durable handlers.
 * Start simple - just creates context and calls user method.
 */
public abstract class DurableHandler<I, O> implements RequestHandler<I, O> {
    
    @Override
    public final O handleRequest(I input, Context lambdaContext) {
        System.out.println("Starting durable execution");
        
        // Create execution state
        ExecutionState state = new ExecutionState(new JacksonSerDes());
        
        // Create durable context
        DurableContext durableContext = new DurableContextImpl(
            state,
            Executors.newFixedThreadPool(10)
        );
        
        // Call user handler
        O output = handleRequest(input, durableContext);
        
        // Cleanup
        state.shutdown();
        
        System.out.println("Durable execution completed");
        return output;
    }
    
    /**
     * User implements this method.
     */
    public abstract O handleRequest(I input, DurableContext context);
}
```

### Step 20.3: Create example handler
```java
package com.amazonaws.lambda.durable.examples;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableHandler;

public class SimpleHandler extends DurableHandler<String, String> {
    
    @Override
    public String handleRequest(String input, DurableContext ctx) {
        String step1 = ctx.step("process", String.class, () -> 
            "Processed: " + input
        );
        
        String step2 = ctx.step("uppercase", String.class, () ->
            step1.toUpperCase()
        );
        
        return step2;
    }
}
```

### Step 20.4: Test handler
```java
@Test
void testSimpleHandler() {
    SimpleHandler handler = new SimpleHandler();
    
    String result = handler.handleRequest("hello", null);
    
    assertEquals("PROCESSED: HELLO", result);
}
```

### ✅ Verify
```bash
mvn test
```

**What you learned:**
- Lambda handler pattern
- Template method pattern
- Base class design

---

## 🎯 Checkpoint: What We Have Now

After 20 increments (~12 hours), you have:

✅ Complete step execution with retry  
✅ Exponential backoff  
✅ Async operations  
✅ Checkpoint batching (100ms window)  
✅ Separate ExecutionState class  
✅ DurableHandler base class  
✅ Working example handler  

**Lines of code:** ~400 LOC

**Next:** Polish, more tests, documentation.

