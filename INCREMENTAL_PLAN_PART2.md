# Incremental Plan - Part 2: Serialization & Advanced Features

**Continuing from Increment 7...**

---

## 🎯 Increment 8: Add Jackson Dependency (10 min)

**Goal:** Add JSON serialization capability.

### Step 8.1: Update pom.xml
```xml
<dependencies>
    <!-- Add Jackson -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.15.0</version>
    </dependency>
    
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### ✅ Verify
```bash
mvn clean compile
```

**What you learned:** Adding Maven dependencies.

---

## 🎯 Increment 9: Simple Serialization (30 min)

**Goal:** Serialize/deserialize results properly.

### Step 9.1: Create SerDes interface
```java
package com.amazonaws.lambda.durable.serde;

/**
 * Minimal serialization interface.
 */
public interface SerDes {
    String serialize(Object value);
    <T> T deserialize(String data, Class<T> type);
}
```

### Step 9.2: Create JacksonSerDes
```java
package com.amazonaws.lambda.durable.serde;

import com.fasterxml.jackson.databind.ObjectMapper;

public class JacksonSerDes implements SerDes {
    private final ObjectMapper mapper = new ObjectMapper();
    
    @Override
    public String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }
    
    @Override
    public <T> T deserialize(String data, Class<T> type) {
        try {
            return mapper.readValue(data, type);
        } catch (Exception e) {
            throw new RuntimeException("Deserialization failed", e);
        }
    }
}
```

### Step 9.3: Test serialization
```java
package com.amazonaws.lambda.durable.serde;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JacksonSerDesTest {
    
    @Test
    void testSerializeString() {
        SerDes serDes = new JacksonSerDes();
        
        String json = serDes.serialize("Hello");
        
        assertEquals("\"Hello\"", json);
    }
    
    @Test
    void testDeserializeString() {
        SerDes serDes = new JacksonSerDes();
        
        String value = serDes.deserialize("\"Hello\"", String.class);
        
        assertEquals("Hello", value);
    }
    
    @Test
    void testSerializeInteger() {
        SerDes serDes = new JacksonSerDes();
        
        String json = serDes.serialize(42);
        
        assertEquals("42", json);
    }
    
    @Test
    void testRoundTrip() {
        SerDes serDes = new JacksonSerDes();
        
        String original = "Test Value";
        String json = serDes.serialize(original);
        String restored = serDes.deserialize(json, String.class);
        
        assertEquals(original, restored);
    }
}
```

### ✅ Verify
```bash
mvn test
```

**What you learned:**
- JSON serialization with Jackson
- Round-trip serialization
- Interface-based design

---

## 🎯 Increment 10: Use SerDes in Context (30 min)

**Goal:** Replace string conversion with proper serialization.

### Step 10.1: Update DurableContextImpl
```java
public class DurableContextImpl implements DurableContext {
    
    private int operationCounter = 0;
    private final Map<String, Operation> checkpointLog = new HashMap<>();
    private final SerDes serDes;  // Add this
    
    public DurableContextImpl() {
        this(new JacksonSerDes());  // Default
    }
    
    public DurableContextImpl(SerDes serDes) {
        this.serDes = serDes;
    }
    
    @Override
    public <T> T step(String name, Class<T> type, Callable<T> action) {
        String opId = String.valueOf(operationCounter++);
        
        // Check replay
        Operation existing = checkpointLog.get(opId);
        if (existing != null) {
            System.out.println("REPLAY: " + name + " (id=" + opId + ")");
            return serDes.deserialize(existing.getResult(), type);  // Use SerDes
        }
        
        // Execute
        try {
            System.out.println("Executing: " + name + " (id=" + opId + ")");
            T result = action.call();
            
            String resultJson = serDes.serialize(result);  // Use SerDes
            checkpointLog.put(opId, new Operation(opId, name, resultJson));
            
            System.out.println("Completed: " + name);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Step failed: " + name, e);
        }
    }
    
    public Map<String, Operation> getCheckpointLog() {
        return checkpointLog;
    }
}
```

### Step 10.2: Test with complex types
```java
@Test
void testComplexTypeReplay() {
    DurableContextImpl ctx = new DurableContextImpl();
    
    // Execute with Integer
    Integer result1 = ctx.step("compute", Integer.class, () -> 42);
    assertEquals(42, result1);
    
    // Create new context with checkpoint log
    DurableContextImpl ctx2 = new DurableContextImpl();
    ctx2.getCheckpointLog().putAll(ctx.getCheckpointLog());
    
    // Replay - should deserialize correctly
    Integer result2 = ctx2.step("compute", Integer.class, () -> 999);
    assertEquals(42, result2);  // Gets cached value, not 999
}
```

### ✅ Verify
```bash
mvn test
```

**What you learned:**
- Dependency injection
- Proper type handling with serialization
- Constructor overloading

---

## 🎯 Increment 11: Add Wait Operation (30 min)

**Goal:** Add simplest wait() method.

### Step 11.1: Add wait() to interface
```java
public interface DurableContext {
    <T> T step(String name, Class<T> type, Callable<T> action);
    
    void wait(Duration duration);  // Add this
}
```

### Step 11.2: Implement wait()
```java
@Override
public void wait(Duration duration) {
    String opId = String.valueOf(operationCounter++);
    
    // Check replay
    Operation existing = checkpointLog.get(opId);
    if (existing != null) {
        System.out.println("REPLAY: wait " + duration + " (id=" + opId + ")");
        return;  // Already waited
    }
    
    // Execute wait
    System.out.println("Waiting: " + duration + " (id=" + opId + ")");
    
    // For now, just checkpoint it (don't actually sleep)
    // Real implementation would suspend execution
    checkpointLog.put(opId, new Operation(opId, "wait", duration.toString()));
    
    System.out.println("Wait completed");
}
```

### Step 11.3: Test wait
```java
@Test
void testWait() {
    DurableContextImpl ctx = new DurableContextImpl();
    
    ctx.step("before", String.class, () -> "A");
    ctx.wait(Duration.ofMinutes(30));
    ctx.step("after", String.class, () -> "B");
    
    // Should have 3 operations
    assertEquals(3, ctx.getCheckpointLog().size());
}

@Test
void testWaitReplay() {
    DurableContextImpl ctx = new DurableContextImpl();
    
    ctx.step("step1", String.class, () -> "A");
    ctx.wait(Duration.ofMinutes(30));
    
    // Create new context with checkpoint
    DurableContextImpl ctx2 = new DurableContextImpl();
    ctx2.getCheckpointLog().putAll(ctx.getCheckpointLog());
    
    // Replay - should skip wait
    ctx2.step("step1", String.class, () -> "A");
    ctx2.wait(Duration.ofMinutes(30));
    ctx2.step("step2", String.class, () -> "B");
    
    // Check console - should see "REPLAY: wait"
}
```

### ✅ Verify
```bash
mvn test
```

**What you learned:**
- Multiple operation types
- Wait operations
- Replay for non-step operations

---

## 🎯 Increment 12: Async Step - DurableFuture Interface (20 min)

**Goal:** Define async API.

### Step 12.1: Create DurableFuture interface
```java
package com.amazonaws.lambda.durable;

/**
 * Handle for async operations.
 * Start with just get() method.
 */
public interface DurableFuture<T> {
    /**
     * Block until result available.
     */
    T get();
    
    /**
     * Check if completed.
     */
    boolean isDone();
}
```

### Step 12.2: Add stepAsync() to DurableContext
```java
public interface DurableContext {
    <T> T step(String name, Class<T> type, Callable<T> action);
    
    <T> DurableFuture<T> stepAsync(String name, Class<T> type, Callable<T> action);  // Add
    
    void wait(Duration duration);
}
```

### ✅ Verify
```bash
mvn clean compile
```

**What you learned:**
- Future pattern
- Async API design

---

## 🎯 Increment 13: Simple DurableFuture Implementation (30 min)

**Goal:** Implement DurableFuture with CompletableFuture.

### Step 13.1: Create DurableFutureImpl
```java
package com.amazonaws.lambda.durable.internal;

import com.amazonaws.lambda.durable.DurableFuture;
import java.util.concurrent.CompletableFuture;

public class DurableFutureImpl<T> implements DurableFuture<T> {
    
    private final CompletableFuture<T> future;
    
    public DurableFutureImpl(CompletableFuture<T> future) {
        this.future = future;
    }
    
    @Override
    public T get() {
        return future.join();  // Block until complete
    }
    
    @Override
    public boolean isDone() {
        return future.isDone();
    }
    
    public static <T> DurableFuture<T> completed(T value) {
        return new DurableFutureImpl<>(CompletableFuture.completedFuture(value));
    }
}
```

### Step 13.2: Test DurableFuture
```java
package com.amazonaws.lambda.durable.internal;

import com.amazonaws.lambda.durable.DurableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DurableFutureImplTest {
    
    @Test
    void testCompletedFuture() {
        DurableFuture<String> future = DurableFutureImpl.completed("test");
        
        assertTrue(future.isDone());
        assertEquals("test", future.get());
    }
}
```

### ✅ Verify
```bash
mvn test
```

**What you learned:**
- CompletableFuture basics
- Wrapping pattern
- Static factory methods

---

## 🎯 Increment 14: Implement stepAsync() (45 min)

**Goal:** Execute steps asynchronously.

### Step 14.1: Add ExecutorService to DurableContextImpl
```java
public class DurableContextImpl implements DurableContext {
    
    private int operationCounter = 0;
    private final Map<String, Operation> checkpointLog = new HashMap<>();
    private final SerDes serDes;
    private final ExecutorService executor;  // Add this
    
    public DurableContextImpl() {
        this(new JacksonSerDes(), Executors.newFixedThreadPool(5));
    }
    
    public DurableContextImpl(SerDes serDes, ExecutorService executor) {
        this.serDes = serDes;
        this.executor = executor;
    }
    
    // ... existing step() and wait() methods ...
}
```

### Step 14.2: Implement stepAsync()
```java
@Override
public <T> DurableFuture<T> stepAsync(String name, Class<T> type, Callable<T> action) {
    String opId = String.valueOf(operationCounter++);
    
    // Check replay
    Operation existing = checkpointLog.get(opId);
    if (existing != null) {
        System.out.println("REPLAY: " + name + " (id=" + opId + ")");
        T result = serDes.deserialize(existing.getResult(), type);
        return DurableFutureImpl.completed(result);
    }
    
    // Execute async
    CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
        try {
            System.out.println("Executing async: " + name + " (id=" + opId + ")");
            T result = action.call();
            
            // Checkpoint
            String resultJson = serDes.serialize(result);
            checkpointLog.put(opId, new Operation(opId, name, resultJson));
            
            System.out.println("Async completed: " + name);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Async step failed: " + name, e);
        }
    }, executor);
    
    return new DurableFutureImpl<>(future);
}
```

### Step 14.3: Test async execution
```java
@Test
void testStepAsync() throws Exception {
    DurableContextImpl ctx = new DurableContextImpl();
    
    DurableFuture<String> future = ctx.stepAsync("async", String.class, () -> {
        Thread.sleep(100);  // Simulate work
        return "async-result";
    });
    
    assertFalse(future.isDone());  // Should not be done immediately
    
    String result = future.get();  // Wait for completion
    
    assertEquals("async-result", result);
    assertTrue(future.isDone());
}

@Test
void testMultipleAsync() {
    DurableContextImpl ctx = new DurableContextImpl();
    
    DurableFuture<Integer> f1 = ctx.stepAsync("async1", Integer.class, () -> 10);
    DurableFuture<Integer> f2 = ctx.stepAsync("async2", Integer.class, () -> 20);
    
    assertEquals(10, f1.get());
    assertEquals(20, f2.get());
}
```

### ✅ Verify
```bash
mvn test
```

**What you learned:**
- Async execution with ExecutorService
- CompletableFuture.supplyAsync()
- Thread pool management

---

## 🎯 Checkpoint: What We Have Now

After 14 increments (~6 hours), you have:

✅ Maven project with Jackson  
✅ DurableContext with step(), stepAsync(), wait()  
✅ Proper serialization with Jackson  
✅ Replay for all operation types  
✅ Async execution with DurableFuture  
✅ Thread pool for concurrency  

**Lines of code:** ~250 LOC

**Next:** Retry logic, checkpoint batching, handler registration.

