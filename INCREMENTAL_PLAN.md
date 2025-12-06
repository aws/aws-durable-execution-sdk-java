# Incremental Implementation Plan - Minimal Testable Steps

**Philosophy:** Each step is minimal, independently testable, and builds understanding incrementally.

**Timeline:** ~3 weeks, but focus on understanding over speed.

---

## 🎯 Increment 1: Project Setup (30 min)

**Goal:** Create minimal Maven project that compiles.

### Step 1.1: Create pom.xml
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.amazonaws</groupId>
    <artifactId>aws-lambda-durable-execution-sdk</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.0</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### Step 1.2: Create directory structure
```bash
mkdir -p src/main/java/com/amazonaws/lambda/durable
mkdir -p src/test/java/com/amazonaws/lambda/durable
```

### ✅ Verify
```bash
mvn clean compile
# Should succeed with no errors
```

**What you learned:** Maven project structure, Java 17 setup.

---

## 🎯 Increment 2: First Interface - DurableContext (20 min)

**Goal:** Define the simplest possible API - just one method.

### Step 2.1: Create DurableContext.java
```java
package com.amazonaws.lambda.durable;

import java.util.concurrent.Callable;

/**
 * Main API for durable execution.
 * Start with just one method - step().
 */
public interface DurableContext {
    /**
     * Execute a step with checkpointing.
     * Blocks until complete.
     */
    <T> T step(String name, Class<T> type, Callable<T> action);
}
```

### ✅ Verify
```bash
mvn clean compile
# Should compile successfully
```

**What you learned:** 
- Generic method signature with `Class<T>` parameter
- `Callable<T>` for user code
- Interface-based API design

---

## 🎯 Increment 3: Simplest Implementation (30 min)

**Goal:** Create implementation that just executes the action (no checkpointing yet).

### Step 3.1: Create DurableContextImpl.java
```java
package com.amazonaws.lambda.durable.internal;

import com.amazonaws.lambda.durable.DurableContext;
import java.util.concurrent.Callable;

/**
 * Simplest implementation - just execute the action.
 * No checkpointing, no replay yet.
 */
public class DurableContextImpl implements DurableContext {
    
    @Override
    public <T> T step(String name, Class<T> type, Callable<T> action) {
        try {
            System.out.println("Executing step: " + name);
            T result = action.call();
            System.out.println("Step completed: " + name + " -> " + result);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Step failed: " + name, e);
        }
    }
}
```

### Step 3.2: Create first test
```java
package com.amazonaws.lambda.durable.internal;

import com.amazonaws.lambda.durable.DurableContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DurableContextImplTest {
    
    @Test
    void testSimpleStep() {
        DurableContext ctx = new DurableContextImpl();
        
        String result = ctx.step("test", String.class, () -> "Hello");
        
        assertEquals("Hello", result);
    }
    
    @Test
    void testStepWithComputation() {
        DurableContext ctx = new DurableContextImpl();
        
        Integer result = ctx.step("add", Integer.class, () -> 5 + 3);
        
        assertEquals(8, result);
    }
}
```

### ✅ Verify
```bash
mvn test
# Both tests should pass
```

**What you learned:**
- Basic implementation pattern
- Exception handling
- Writing unit tests
- Generic type handling

---

## 🎯 Increment 4: Add Operation Counter (20 min)

**Goal:** Generate deterministic operation IDs.

### Step 4.1: Update DurableContextImpl
```java
public class DurableContextImpl implements DurableContext {
    
    private int operationCounter = 0;  // Add this
    
    @Override
    public <T> T step(String name, Class<T> type, Callable<T> action) {
        String opId = String.valueOf(operationCounter++);  // Generate ID
        
        try {
            System.out.println("Executing step: " + name + " (id=" + opId + ")");
            T result = action.call();
            System.out.println("Step completed: " + name + " -> " + result);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Step failed: " + name, e);
        }
    }
}
```

### Step 4.2: Test operation IDs
```java
@Test
void testOperationIds() {
    DurableContext ctx = new DurableContextImpl();
    
    // Execute multiple steps
    ctx.step("step1", String.class, () -> "A");
    ctx.step("step2", String.class, () -> "B");
    ctx.step("step3", String.class, () -> "C");
    
    // Check console output - should see id=0, id=1, id=2
}
```

### ✅ Verify
```bash
mvn test
# Check console output for sequential IDs
```

**What you learned:**
- Deterministic ID generation
- Why sequential IDs matter for replay

---

## 🎯 Increment 5: Operation Model (30 min)

**Goal:** Create data structure to represent an operation.

### Step 5.1: Create Operation.java
```java
package com.amazonaws.lambda.durable.internal.model;

/**
 * Represents a single operation in checkpoint log.
 * Start with minimal fields.
 */
public class Operation {
    private final String id;
    private final String name;
    private final String result;
    
    public Operation(String id, String name, String result) {
        this.id = id;
        this.name = name;
        this.result = result;
    }
    
    public String getId() { return id; }
    public String getName() { return name; }
    public String getResult() { return result; }
}
```

### Step 5.2: Test Operation
```java
package com.amazonaws.lambda.durable.internal.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OperationTest {
    
    @Test
    void testCreateOperation() {
        Operation op = new Operation("0", "test-step", "result-value");
        
        assertEquals("0", op.getId());
        assertEquals("test-step", op.getName());
        assertEquals("result-value", op.getResult());
    }
}
```

### ✅ Verify
```bash
mvn test
```

**What you learned:**
- Data modeling
- Immutable objects
- Simple getters

---

## 🎯 Increment 6: In-Memory Checkpoint Log (30 min)

**Goal:** Store completed operations in memory.

### Step 6.1: Add checkpoint log to DurableContextImpl
```java
public class DurableContextImpl implements DurableContext {
    
    private int operationCounter = 0;
    private final Map<String, Operation> checkpointLog = new HashMap<>();  // Add this
    
    @Override
    public <T> T step(String name, Class<T> type, Callable<T> action) {
        String opId = String.valueOf(operationCounter++);
        
        try {
            System.out.println("Executing step: " + name + " (id=" + opId + ")");
            T result = action.call();
            
            // Store in checkpoint log
            String resultStr = String.valueOf(result);
            checkpointLog.put(opId, new Operation(opId, name, resultStr));
            
            System.out.println("Step completed: " + name + " -> " + result);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Step failed: " + name, e);
        }
    }
    
    // Add method to inspect checkpoint log
    public Map<String, Operation> getCheckpointLog() {
        return checkpointLog;
    }
}
```

### Step 6.2: Test checkpoint log
```java
@Test
void testCheckpointLog() {
    DurableContextImpl ctx = new DurableContextImpl();
    
    ctx.step("step1", String.class, () -> "A");
    ctx.step("step2", Integer.class, () -> 42);
    
    Map<String, Operation> log = ctx.getCheckpointLog();
    
    assertEquals(2, log.size());
    assertEquals("step1", log.get("0").getName());
    assertEquals("A", log.get("0").getResult());
    assertEquals("step2", log.get("1").getName());
    assertEquals("42", log.get("1").getResult());
}
```

### ✅ Verify
```bash
mvn test
```

**What you learned:**
- In-memory state management
- Map-based storage
- Checkpoint concept

---

## 🎯 Increment 7: Basic Replay (45 min)

**Goal:** Skip execution if operation already in checkpoint log.

### Step 7.1: Add replay check
```java
@Override
public <T> T step(String name, Class<T> type, Callable<T> action) {
    String opId = String.valueOf(operationCounter++);
    
    // Check if already completed (REPLAY)
    Operation existing = checkpointLog.get(opId);
    if (existing != null) {
        System.out.println("REPLAY: Skipping step: " + name + " (id=" + opId + ")");
        // For now, just return the string result
        // We'll add proper deserialization later
        return (T) existing.getResult();
    }
    
    // Not in log - execute normally
    try {
        System.out.println("Executing step: " + name + " (id=" + opId + ")");
        T result = action.call();
        
        String resultStr = String.valueOf(result);
        checkpointLog.put(opId, new Operation(opId, name, resultStr));
        
        System.out.println("Step completed: " + name + " -> " + result);
        return result;
    } catch (Exception e) {
        throw new RuntimeException("Step failed: " + name, e);
    }
}
```

### Step 7.2: Test replay
```java
@Test
void testReplay() {
    DurableContextImpl ctx = new DurableContextImpl();
    
    // First execution - should execute
    AtomicInteger executionCount = new AtomicInteger(0);
    String result1 = ctx.step("test", String.class, () -> {
        executionCount.incrementAndGet();
        return "executed";
    });
    
    assertEquals("executed", result1);
    assertEquals(1, executionCount.get());
    
    // Create new context with same checkpoint log
    DurableContextImpl ctx2 = new DurableContextImpl();
    ctx2.getCheckpointLog().putAll(ctx.getCheckpointLog());
    
    // Second execution - should replay (not execute)
    String result2 = ctx2.step("test", String.class, () -> {
        executionCount.incrementAndGet();
        return "executed-again";
    });
    
    assertEquals("executed", result2);  // Returns cached result
    assertEquals(1, executionCount.get());  // Action not called again
}
```

### ✅ Verify
```bash
mvn test
# Check console - should see "REPLAY: Skipping step"
```

**What you learned:**
- Replay mechanism
- How checkpoints prevent re-execution
- State transfer between contexts

---

## 🎯 Checkpoint: What We Have So Far

After 7 increments (~3 hours), you have:

✅ Maven project that compiles  
✅ DurableContext interface with step() method  
✅ DurableContextImpl that executes steps  
✅ Deterministic operation IDs  
✅ Operation model  
✅ In-memory checkpoint log  
✅ Basic replay (skips completed steps)  

**Lines of code:** ~100 LOC

**Next:** We'll add serialization, then async operations, then batching.

