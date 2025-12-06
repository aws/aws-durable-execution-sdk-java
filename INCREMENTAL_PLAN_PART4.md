# Incremental Plan - Part 4: Testing, Polish & Completion

**Continuing from Increment 20...**

---

## 🎯 Increment 21: End-to-End Test (30 min)

**Goal:** Test complete workflow with replay.

### Step 21.1: Create comprehensive test
```java
package com.amazonaws.lambda.durable.integration;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableHandler;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EndToEndTest {
    
    static class TestHandler extends DurableHandler<String, String> {
        static AtomicInteger executionCount = new AtomicInteger(0);
        
        @Override
        public String handleRequest(String input, DurableContext ctx) {
            String step1 = ctx.step("step1", String.class, () -> {
                executionCount.incrementAndGet();
                return "Step1: " + input;
            });
            
            String step2 = ctx.step("step2", String.class, () -> {
                executionCount.incrementAndGet();
                return step1 + " -> Step2";
            });
            
            return step2;
        }
    }
    
    @Test
    void testCompleteWorkflow() {
        TestHandler handler = new TestHandler();
        TestHandler.executionCount.set(0);
        
        String result = handler.handleRequest("test", null);
        
        assertEquals("Step1: test -> Step2", result);
        assertEquals(2, TestHandler.executionCount.get());
    }
}
```

### ✅ Verify
```bash
mvn test
```

**What you learned:** Integration testing patterns.

---

## 🎯 Increment 22: Add Exception Classes (20 min)

**Goal:** Proper exception hierarchy.

### Step 22.1: Create base exception
```java
package com.amazonaws.lambda.durable.exception;

public class DurableExecutionException extends RuntimeException {
    public DurableExecutionException(String message) {
        super(message);
    }
    
    public DurableExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### Step 22.2: Create StepFailedException
```java
package com.amazonaws.lambda.durable.exception;

public class StepFailedException extends DurableExecutionException {
    private final int attempts;
    
    public StepFailedException(String message, int attempts, Throwable cause) {
        super(message + " (after " + attempts + " attempts)", cause);
        this.attempts = attempts;
    }
    
    public int getAttempts() {
        return attempts;
    }
}
```

### Step 22.3: Use in DurableContextImpl
```java
// Replace generic RuntimeException with:
throw new StepFailedException("Step failed: " + name, policy.getMaxAttempts(), lastException);
```

### ✅ Verify
```bash
mvn test
```

**What you learned:** Custom exception design.

---

## 🎯 Increment 23: Add Logging (30 min)

**Goal:** Replace System.out with proper logging.

### Step 23.1: Add SLF4J dependency
```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>2.0.9</version>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.9</version>
    <scope>test</scope>
</dependency>
```

### Step 23.2: Add logger to DurableContextImpl
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DurableContextImpl implements DurableContext {
    private static final Logger log = LoggerFactory.getLogger(DurableContextImpl.class);
    
    // Replace System.out.println with:
    log.info("Executing: {} (id={})", name, opId);
    log.info("REPLAY: {} (id={})", name, opId);
    // etc.
}
```

### ✅ Verify
```bash
mvn test
# Should see proper log output
```

**What you learned:** Logging best practices.

---

## 🎯 Increment 24: Add JavaDoc (30 min)

**Goal:** Document public API.

### Step 24.1: Add JavaDoc to DurableContext
```java
/**
 * Main API for durable execution operations.
 * 
 * <p>Provides methods for executing steps with automatic checkpointing,
 * replay, and retry capabilities.
 * 
 * <p>Example usage:
 * <pre>{@code
 * public class MyHandler extends DurableHandler<Input, Output> {
 *     public Output handleRequest(Input input, DurableContext ctx) {
 *         String result = ctx.step("process", String.class, () -> 
 *             processData(input)
 *         );
 *         return new Output(result);
 *     }
 * }
 * }</pre>
 */
public interface DurableContext {
    
    /**
     * Execute a step with automatic checkpointing and replay.
     * 
     * <p>If this step has already completed (found in checkpoint log),
     * returns the cached result without re-executing the action.
     * 
     * <p>On failure, retries according to the default retry policy.
     * 
     * @param name Step name for logging and debugging
     * @param type Return type class (required for deserialization)
     * @param action Code to execute
     * @param <T> Return type
     * @return Step result
     * @throws StepFailedException if step fails after all retries
     */
    <T> T step(String name, Class<T> type, Callable<T> action);
    
    // ... document other methods ...
}
```

### ✅ Verify
```bash
mvn javadoc:javadoc
# Check target/site/apidocs/
```

**What you learned:** JavaDoc documentation.

---

## 🎯 Increment 25: Create README (30 min)

**Goal:** Document usage for users.

### Step 25.1: Create comprehensive README
```markdown
# AWS Lambda Durable Execution SDK for Java

Simple Java SDK for AWS Lambda Durable Executions.

## Features

- ✅ Automatic checkpointing and replay
- ✅ Retry with exponential backoff
- ✅ Async operations
- ✅ Wait operations
- ✅ Simple, idiomatic Java API

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>com.amazonaws</groupId>
    <artifactId>aws-lambda-durable-execution-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2. Create Handler

```java
public class OrderProcessor extends DurableHandler<OrderEvent, OrderResult> {
    @Override
    public OrderResult handleRequest(OrderEvent event, DurableContext ctx) {
        // Step 1: Process order
        Order order = ctx.step("process", Order.class, () -> 
            processOrder(event)
        );
        
        // Step 2: Wait 30 minutes
        ctx.wait(Duration.ofMinutes(30));
        
        // Step 3: Complete order
        String confirmation = ctx.step("complete", String.class, () ->
            completeOrder(order)
        );
        
        return new OrderResult(confirmation);
    }
}
```

## API Overview

### DurableContext

Main API for durable operations.

**step(name, type, action)** - Execute with checkpointing
**stepAsync(name, type, action)** - Execute asynchronously
**wait(duration)** - Pause execution

### DurableHandler<I, O>

Base class for Lambda handlers.

## How It Works

1. **Checkpointing**: Each step saves its result
2. **Replay**: On resume, completed steps return cached results
3. **Retry**: Failed steps retry with exponential backoff
4. **Suspension**: Wait operations pause execution (no charges)

## Examples

See `examples/` directory for complete examples.

## License

Apache 2.0
```

### ✅ Verify
```bash
# Read README.md
```

**What you learned:** Technical documentation.

---

## 🎯 Increment 26: Performance Test (30 min)

**Goal:** Verify checkpoint batching works.

### Step 26.1: Create performance test
```java
package com.amazonaws.lambda.durable.performance;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.internal.DurableContextImpl;
import com.amazonaws.lambda.durable.internal.ExecutionState;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;

class PerformanceTest {
    
    @Test
    void testCheckpointBatchingPerformance() throws Exception {
        ExecutionState state = new ExecutionState(new JacksonSerDes());
        DurableContext ctx = new DurableContextImpl(state, Executors.newFixedThreadPool(10));
        
        long start = System.currentTimeMillis();
        
        // Execute 100 steps
        for (int i = 0; i < 100; i++) {
            final int index = i;
            ctx.step("step-" + i, Integer.class, () -> index);
        }
        
        // Wait for batching
        Thread.sleep(200);
        
        long duration = System.currentTimeMillis() - start;
        
        System.out.println("Executed 100 steps in " + duration + "ms");
        System.out.println("Average: " + (duration / 100.0) + "ms per step");
        
        // Verify all checkpointed
        assertEquals(100, state.getCheckpointLog().size());
        
        state.shutdown();
    }
}
```

### ✅ Verify
```bash
mvn test
# Check performance metrics in console
```

**What you learned:** Performance testing.

---

## 🎯 Increment 27: Add Builder Pattern (30 min)

**Goal:** Make RetryPolicy easier to configure.

### Step 27.1: Add builder to RetryPolicy
```java
public class RetryPolicy {
    private final int maxAttempts;
    private final Duration initialInterval;
    private final double backoffCoefficient;
    
    private RetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialInterval = builder.initialInterval;
        this.backoffCoefficient = builder.backoffCoefficient;
    }
    
    // ... getters and calculateDelay() ...
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static RetryPolicy defaultPolicy() {
        return builder().build();
    }
    
    public static class Builder {
        private int maxAttempts = 3;
        private Duration initialInterval = Duration.ofSeconds(1);
        private double backoffCoefficient = 2.0;
        
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }
        
        public Builder initialInterval(Duration initialInterval) {
            this.initialInterval = initialInterval;
            return this;
        }
        
        public Builder backoffCoefficient(double backoffCoefficient) {
            this.backoffCoefficient = backoffCoefficient;
            return this;
        }
        
        public RetryPolicy build() {
            return new RetryPolicy(this);
        }
    }
}
```

### Step 27.2: Test builder
```java
@Test
void testBuilder() {
    RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(5)
        .initialInterval(Duration.ofMillis(500))
        .backoffCoefficient(3.0)
        .build();
    
    assertEquals(5, policy.getMaxAttempts());
    assertEquals(Duration.ofMillis(500), policy.getInitialInterval());
    assertEquals(3.0, policy.getBackoffCoefficient());
}
```

### ✅ Verify
```bash
mvn test
```

**What you learned:** Builder pattern.

---

## 🎯 Increment 28: Add More Examples (30 min)

**Goal:** Show different usage patterns.

### Step 28.1: Create async example
```java
package com.amazonaws.lambda.durable.examples;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableFuture;
import com.amazonaws.lambda.durable.DurableHandler;

public class AsyncExample extends DurableHandler<String, String> {
    
    @Override
    public String handleRequest(String input, DurableContext ctx) {
        // Start multiple async operations
        DurableFuture<String> f1 = ctx.stepAsync("fetch1", String.class, () ->
            fetchData1(input)
        );
        
        DurableFuture<String> f2 = ctx.stepAsync("fetch2", String.class, () ->
            fetchData2(input)
        );
        
        // Wait for both
        String data1 = f1.get();
        String data2 = f2.get();
        
        // Combine results
        return ctx.step("combine", String.class, () ->
            data1 + " + " + data2
        );
    }
    
    private String fetchData1(String input) {
        return "Data1(" + input + ")";
    }
    
    private String fetchData2(String input) {
        return "Data2(" + input + ")";
    }
}
```

### Step 28.2: Create retry example
```java
public class RetryExample extends DurableHandler<String, String> {
    
    @Override
    public String handleRequest(String input, DurableContext ctx) {
        RetryPolicy policy = RetryPolicy.builder()
            .maxAttempts(5)
            .initialInterval(Duration.ofSeconds(2))
            .backoffCoefficient(2.0)
            .build();
        
        return ctx.step("unreliable-api", String.class, () ->
            callUnreliableAPI(input), policy
        );
    }
    
    private String callUnreliableAPI(String input) {
        // Simulates unreliable external API
        if (Math.random() < 0.7) {
            throw new RuntimeException("API temporarily unavailable");
        }
        return "Success: " + input;
    }
}
```

### ✅ Verify
```bash
mvn test
```

**What you learned:** Different usage patterns.

---

## 🎯 Increment 29: Code Cleanup (30 min)

**Goal:** Remove debug code, polish implementation.

### Step 29.1: Review and clean
- Remove unnecessary System.out.println
- Ensure all logging uses SLF4J
- Add final keywords where appropriate
- Fix any compiler warnings
- Format code consistently

### Step 29.2: Run full test suite
```bash
mvn clean test
```

### ✅ Verify
```bash
mvn clean compile
# No warnings

mvn test
# All tests pass
```

**What you learned:** Code quality practices.

---

## 🎯 Increment 30: Final Verification (30 min)

**Goal:** Ensure everything works end-to-end.

### Step 30.1: Create comprehensive integration test
```java
@Test
void testCompleteSDK() throws Exception {
    // Test all features together
    TestHandler handler = new TestHandler();
    
    String result = handler.handleRequest("test-input", null);
    
    assertNotNull(result);
    assertTrue(result.contains("test-input"));
}

@Test
void testReplayScenario() {
    // Simulate execution -> interruption -> replay
    // This would require more infrastructure in real implementation
}
```

### Step 30.2: Verify all tests pass
```bash
mvn clean test
```

### Step 30.3: Generate coverage report
```bash
mvn jacoco:prepare-agent test jacoco:report
open target/site/jacoco/index.html
```

### ✅ Final Checklist

- [ ] All tests pass
- [ ] No compiler warnings
- [ ] JavaDoc complete
- [ ] README complete
- [ ] Examples work
- [ ] ~500 LOC core code
- [ ] Test coverage > 70%

---

## 🎉 Complete!

After 30 increments (~15-18 hours), you have:

✅ **Core Features**
- Step execution with checkpointing
- Replay from checkpoint log
- Retry with exponential backoff
- Async operations
- Wait operations
- Checkpoint batching

✅ **Quality**
- Comprehensive test suite
- Proper exception handling
- Logging with SLF4J
- JavaDoc documentation
- Multiple examples

✅ **Code**
- ~500 LOC core implementation
- Clean, maintainable code
- Idiomatic Java patterns

## 📊 Final Metrics

**Lines of Code:**
- Core: ~500 LOC
- Tests: ~300 LOC
- Examples: ~100 LOC
- Total: ~900 LOC

**Test Coverage:** > 70%

**Features Implemented:**
- ✅ step() with replay
- ✅ stepAsync() with futures
- ✅ wait() operations
- ✅ Retry with exponential backoff
- ✅ Checkpoint batching
- ✅ Handler base class
- ✅ Serialization with Jackson

**What You Learned:**
- Incremental development
- Test-driven development
- Java 17 patterns
- Async programming
- Checkpoint/replay concepts
- SDK design

## 🚀 Next Steps

1. **Integration with AWS** - Add real Lambda API calls
2. **More features** - parallel(), invoke(), callbacks
3. **Production hardening** - Error handling, edge cases
4. **Performance tuning** - Optimize batching, threading
5. **Documentation** - More examples, tutorials

**Congratulations!** You've built a functional durable execution SDK incrementally, understanding each piece deeply.

