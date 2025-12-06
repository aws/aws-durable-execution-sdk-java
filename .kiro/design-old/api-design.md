# API Design - AWS Lambda Durable Execution Java SDK

**Version:** 2.0  
**Date:** December 6, 2025  
**Status:** Active Design - PoC Ready

## Overview

This document specifies the complete public API for the Java SDK. This is a **fresh implementation** designed for simplicity and Java idioms.

**Design Goals:**
- Simple, blocking API by default
- Async operations available when needed
- Type-safe with generics
- Consistent with TypeScript/Python SDKs
- ~500 LOC core implementation

## 1. Core Interfaces

### 1.1 DurableContext

The main API that users interact with.

```java
package com.amazonaws.lambda.durable;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Context for durable execution operations.
 * Provides step execution, wait operations, and execution metadata.
 */
public interface DurableContext {
    
    // ===== Step Execution =====
    
    /**
     * Execute a step with automatic checkpointing and replay.
     * Blocks until step completes.
     * 
     * @param name Unique step name for replay identification
     * @param type Result type class (needed for deserialization)
     * @param action The step logic to execute
     * @return The step result
     */
    <T> T step(String name, Class<T> type, Callable<T> action);
    
    /**
     * Execute a step asynchronously.
     * Returns immediately with a DurableFuture.
     * 
     * @param name Unique step name
     * @param type Result type class
     * @param action The step logic
     * @return Future that completes when step finishes
     */
    <T> DurableFuture<T> stepAsync(String name, Class<T> type, Callable<T> action);
    
    // ===== Wait Operations =====
    
    /**
     * Wait for a duration. Blocks until duration elapses.
     * Execution suspends and resumes after duration.
     * 
     * @param duration How long to wait
     */
    void wait(Duration duration);
    
    /**
     * Start a timer asynchronously.
     * Returns immediately with a DurableFuture.
     * 
     * @param duration How long to wait
     * @return Future that completes after duration
     */
    DurableFuture<Void> timer(Duration duration);
    
    // ===== Execution Metadata =====
    
    /**
     * Get the durable execution ARN.
     */
    String getExecutionArn();
    
    /**
     * Get the current Lambda request ID.
     */
    String getRequestId();
}
```

### 1.2 DurableFuture

Async operation handle with replay-safe semantics.

```java
package com.amazonaws.lambda.durable;

import java.util.function.Function;

/**
 * A replay-safe future for async durable operations.
 * Similar to CompletableFuture but checkpoint-aware.
 */
public interface DurableFuture<T> {
    
    /**
     * Block until the future completes and return the result.
     * Similar to CompletableFuture.join() but with durable semantics.
     * 
     * @return The result value
     * @throws RuntimeException if the operation failed
     */
    T get();
    
    /**
     * Check if the future has completed.
     */
    boolean isDone();
    
    /**
     * Transform the result when complete.
     * 
     * @param mapper Function to transform the result
     * @return New future with transformed result
     */
    <R> DurableFuture<R> map(Function<T, R> mapper);
    
    /**
     * Chain another async operation.
     * 
     * @param mapper Function that returns another DurableFuture
     * @return New future from the chained operation
     */
    <R> DurableFuture<R> flatMap(Function<T, DurableFuture<R>> mapper);
}
```

## 2. Handler Registration

### 2.1 DurableHandler (Base Class Pattern)

**Recommended approach** - Extend this base class.

```java
package com.amazonaws.lambda.durable;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.*;

/**
 * Base class for durable execution handlers.
 * Extend this class and implement handleRequest() with your durable logic.
 * 
 * @param <I> Input event type
 * @param <O> Output result type
 */
public abstract class DurableHandler<I, O> implements RequestStreamHandler {
    
    /**
     * User implements this method with their durable logic.
     * 
     * @param input The deserialized input event
     * @param context The durable execution context
     * @return The result to be serialized and returned
     */
    public abstract O handleRequest(I input, DurableContext context);
    
    /**
     * Lambda calls this method. SDK handles:
     * - Parsing durable execution envelope
     * - Creating DurableContext with execution state
     * - Deserializing user's input event
     * - Calling user's handleRequest()
     * - Handling suspension/completion
     * - Serializing response
     */
    @Override
    public final void handleRequest(InputStream inputStream, 
                                    OutputStream outputStream, 
                                    Context lambdaContext) throws IOException {
        // SDK implementation
    }
}
```

**Usage:**
```java
public class OrderProcessor extends DurableHandler<OrderEvent, OrderResult> {
    @Override
    public OrderResult handleRequest(OrderEvent event, DurableContext ctx) {
        String result = ctx.step("process", String.class, () -> 
            processOrder(event)
        );
        ctx.wait(Duration.ofHours(24));
        return new OrderResult(result);
    }
}
```

### 2.2 DurableExecution (Wrapper Pattern)

**Alternative approach** - Wrap a handler function.

```java
package com.amazonaws.lambda.durable;

import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.util.function.BiFunction;

/**
 * Utility for wrapping handler functions as durable executions.
 * Alternative to extending DurableHandler base class.
 */
public final class DurableExecution {
    
    /**
     * Wrap a handler function for durable execution.
     * 
     * @param handler The handler function (input, context) -> output
     * @param inputType Class of input type (needed due to type erasure)
     * @param outputType Class of output type (needed due to type erasure)
     * @return RequestStreamHandler that can be used as Lambda handler
     */
    public static <I, O> RequestStreamHandler wrap(
        BiFunction<I, DurableContext, O> handler,
        Class<I> inputType,
        Class<O> outputType
    ) {
        // SDK implementation
        return null;
    }
}
```

**Usage:**
```java
public class OrderProcessor {
    public static final RequestStreamHandler HANDLER = 
        DurableExecution.wrap(
            OrderProcessor::handleOrder,
            OrderEvent.class,
            OrderResult.class
        );
    
    public static OrderResult handleOrder(OrderEvent event, DurableContext ctx) {
        String result = ctx.step("process", String.class, () -> 
            processOrder(event)
        );
        ctx.wait(Duration.ofHours(24));
        return new OrderResult(result);
    }
}
```

## 3. Configuration Classes

### 3.1 StepConfig

```java
package com.amazonaws.lambda.durable.config;

/**
 * Configuration for step execution.
 */
public final class StepConfig {
    private final RetryPolicy retryPolicy;
    private final StepSemantics semantics;
    
    public enum StepSemantics {
        AT_LEAST_ONCE,  // Default: May execute multiple times on retry
        AT_MOST_ONCE    // Execute once, don't retry on failure
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private RetryPolicy retryPolicy;
        private StepSemantics semantics = StepSemantics.AT_LEAST_ONCE;
        
        public Builder retryPolicy(RetryPolicy policy) {
            this.retryPolicy = policy;
            return this;
        }
        
        public Builder semantics(StepSemantics semantics) {
            this.semantics = semantics;
            return this;
        }
        
        public StepConfig build() {
            return new StepConfig(this);
        }
    }
}
```

### 3.2 RetryPolicy

```java
package com.amazonaws.lambda.durable.config;

import java.time.Duration;

/**
 * Retry policy for step execution.
 */
public final class RetryPolicy {
    private final int maxAttempts;
    private final Duration initialInterval;
    private final Duration maxInterval;
    private final double backoffCoefficient;
    
    /**
     * Create exponential backoff retry policy.
     * Default: 7 attempts with 1s, 2s, 4s, 8s, 16s, 32s, 60s delays.
     */
    public static RetryPolicy exponentialBackoff() {
        return exponentialBackoff(7);
    }
    
    public static RetryPolicy exponentialBackoff(int maxAttempts) {
        return builder()
            .maxAttempts(maxAttempts)
            .initialInterval(Duration.ofSeconds(1))
            .maxInterval(Duration.ofSeconds(60))
            .backoffCoefficient(2.0)
            .build();
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private int maxAttempts = 7;
        private Duration initialInterval = Duration.ofSeconds(1);
        private Duration maxInterval = Duration.ofSeconds(60);
        private double backoffCoefficient = 2.0;
        
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }
        
        public Builder initialInterval(Duration interval) {
            this.initialInterval = interval;
            return this;
        }
        
        public Builder maxInterval(Duration interval) {
            this.maxInterval = interval;
            return this;
        }
        
        public Builder backoffCoefficient(double coefficient) {
            this.backoffCoefficient = coefficient;
            return this;
        }
        
        public RetryPolicy build() {
            return new RetryPolicy(this);
        }
    }
}
```

## 4. Serialization

### 4.1 SerDes Interface

```java
package com.amazonaws.lambda.durable.serde;

/**
 * Pluggable serialization/deserialization interface.
 */
public interface SerDes {
    
    /**
     * Serialize an object to JSON string.
     */
    String serialize(Object obj);
    
    /**
     * Deserialize JSON string to object of specified type.
     */
    <T> T deserialize(String json, Class<T> type);
}
```

### 4.2 JacksonSerDes (Default Implementation)

```java
package com.amazonaws.lambda.durable.serde;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Jackson-based SerDes implementation (default).
 */
public class JacksonSerDes implements SerDes {
    private final ObjectMapper mapper;
    
    public JacksonSerDes() {
        this.mapper = new ObjectMapper();
    }
    
    public JacksonSerDes(ObjectMapper mapper) {
        this.mapper = mapper;
    }
    
    @Override
    public String serialize(Object obj) {
        // Implementation
        return null;
    }
    
    @Override
    public <T> T deserialize(String json, Class<T> type) {
        // Implementation
        return null;
    }
}
```

## 5. Exception Hierarchy

```java
package com.amazonaws.lambda.durable;

/**
 * Base exception for all durable execution errors.
 */
public class DurableExecutionException extends RuntimeException {
    public DurableExecutionException(String message) {
        super(message);
    }
    
    public DurableExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * Thrown when a step fails after all retry attempts.
 */
public class StepFailedException extends DurableExecutionException {
    private final int attempts;
    
    public StepFailedException(String stepName, int attempts, Throwable cause) {
        super("Step '" + stepName + "' failed after " + attempts + " attempts", cause);
        this.attempts = attempts;
    }
    
    public int getAttempts() {
        return attempts;
    }
}

/**
 * Thrown when checkpoint operation fails.
 */
public class CheckpointException extends DurableExecutionException {
    public CheckpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

## 6. Package Structure

```
com.amazonaws.lambda.durable/
├── DurableContext.java              # Main context interface
├── DurableHandler.java              # Base class for handlers
├── DurableExecution.java            # Wrapper utility
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
    └── ... (internal classes)
```

## 7. Usage Examples

### 7.1 Simple Sequential Workflow

```java
public class OrderWorkflow extends DurableHandler<OrderEvent, OrderResult> {
    @Override
    public OrderResult handleRequest(OrderEvent event, DurableContext ctx) {
        // Step 1: Validate order
        Order order = ctx.step("validate", Order.class, () -> 
            validateOrder(event)
        );
        
        // Step 2: Wait for payment
        ctx.wait(Duration.ofMinutes(30));
        
        // Step 3: Process order
        String confirmation = ctx.step("process", String.class, () ->
            processOrder(order)
        );
        
        return new OrderResult(confirmation);
    }
}
```

### 7.2 Async Composition

```java
public class DataPipeline extends DurableHandler<PipelineInput, PipelineResult> {
    @Override
    public PipelineResult handleRequest(PipelineInput input, DurableContext ctx) {
        // Start two async operations
        DurableFuture<String> data1 = ctx.stepAsync("fetch1", String.class, () ->
            fetchData1(input)
        );
        
        DurableFuture<String> data2 = ctx.stepAsync("fetch2", String.class, () ->
            fetchData2(input)
        );
        
        // Compose results
        DurableFuture<String> combined = data1.flatMap(d1 ->
            data2.map(d2 -> combineData(d1, d2))
        );
        
        // Block until complete
        String result = combined.get();
        
        return new PipelineResult(result);
    }
}
```

### 7.3 With Retry Configuration

```java
public class ResilientWorkflow extends DurableHandler<Input, Output> {
    @Override
    public Output handleRequest(Input input, DurableContext ctx) {
        // Step with custom retry policy
        String result = ctx.step("risky-operation", String.class,
            StepConfig.builder()
                .retryPolicy(RetryPolicy.exponentialBackoff(5))
                .build(),
            () -> riskyOperation(input)
        );
        
        return new Output(result);
    }
}
```

## 8. Testing

### 8.1 Mocking DurableContext

```java
@Test
void testOrderWorkflow() {
    // Create mock context
    DurableContext mockCtx = mock(DurableContext.class);
    
    // Setup step responses
    when(mockCtx.step(eq("validate"), eq(Order.class), any()))
        .thenReturn(new Order("123", "item"));
    when(mockCtx.step(eq("process"), eq(String.class), any()))
        .thenReturn("ORDER-123");
    
    // Test handler
    OrderWorkflow handler = new OrderWorkflow();
    OrderEvent event = new OrderEvent("123", "item");
    OrderResult result = handler.handleRequest(event, mockCtx);
    
    // Verify
    assertEquals("ORDER-123", result.getConfirmation());
    verify(mockCtx).wait(Duration.ofMinutes(30));
}
```

## 9. Summary

This API provides:

✅ **Simple blocking operations** - `step()`, `wait()`  
✅ **Async when needed** - `stepAsync()`, `timer()`, `DurableFuture`  
✅ **Type safety** - Generics with Class<T> parameters  
✅ **Flexible handlers** - Base class OR wrapper function  
✅ **Configurable** - Retry policies, step semantics  
✅ **Testable** - Easy to mock DurableContext  
✅ **Idiomatic Java** - Standard patterns (Future, Callable, Builder)  

**Next:** See `implementation-strategy.md` for implementation details.
