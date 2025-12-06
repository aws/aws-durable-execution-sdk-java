# Handler Registration Patterns

**Date:** December 6, 2025  
**Status:** Active Design

## Overview

The Java SDK supports two patterns for registering durable execution handlers:

1. **Base Class Pattern** (Recommended) - Extend `DurableHandler<I, O>`
2. **Wrapper Function Pattern** (Alternative) - Use `DurableExecution.wrap()`

Both patterns provide the same functionality. Choose based on your preference and use case.

## 1. Base Class Pattern (Recommended)

### API

```java
package com.amazonaws.lambda.durable;

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
     * Lambda calls this method. SDK handles envelope parsing,
     * context creation, serialization, and suspension/completion.
     */
    @Override
    public final void handleRequest(InputStream inputStream, 
                                    OutputStream outputStream, 
                                    Context lambdaContext) throws IOException {
        // SDK implementation
    }
}
```

### Usage Example

```java
package com.example;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableHandler;
import java.time.Duration;

public class OrderProcessor extends DurableHandler<OrderEvent, OrderResult> {
    
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
    
    private Order validateOrder(OrderEvent event) {
        // Validation logic
        return new Order(event.getOrderId(), event.getItems());
    }
    
    private String processOrder(Order order) {
        // Processing logic
        return "ORDER-" + order.getId();
    }
}
```

**Lambda Configuration:**
```
Handler: com.example.OrderProcessor::handleRequest
Runtime: java17
```

### Advantages

✅ **Type Safety** - Generics provide compile-time type checking  
✅ **No Type Parameters** - Avoids Java type erasure issues  
✅ **Standard Pattern** - Familiar to Lambda developers (like `RequestHandler`)  
✅ **Clean Syntax** - No wrapper boilerplate  
✅ **IDE Support** - Better autocomplete and refactoring  

### When to Use

- New projects
- When you want strong type safety
- When you prefer object-oriented style
- When you have multiple handlers in separate classes

## 2. Wrapper Function Pattern (Alternative)

### API

```java
package com.amazonaws.lambda.durable;

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
        return new RequestStreamHandler() {
            @Override
            public void handleRequest(InputStream input, 
                                     OutputStream output, 
                                     Context context) throws IOException {
                // SDK implementation - same as DurableHandler
            }
        };
    }
    
    /**
     * Wrap a void handler function (no return value).
     */
    public static <I> RequestStreamHandler wrapVoid(
        BiConsumer<I, DurableContext> handler,
        Class<I> inputType
    ) {
        return wrap((input, ctx) -> {
            handler.accept(input, ctx);
            return null;
        }, inputType, Void.class);
    }
}
```

### Usage Example

```java
package com.example;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableExecution;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.time.Duration;

public class OrderProcessor {
    
    // Define the handler as a static method
    public static OrderResult handleOrder(OrderEvent event, DurableContext ctx) {
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
    
    // Wrap the handler for Lambda
    public static final RequestStreamHandler HANDLER = 
        DurableExecution.wrap(
            OrderProcessor::handleOrder,
            OrderEvent.class,
            OrderResult.class
        );
    
    private static Order validateOrder(OrderEvent event) {
        return new Order(event.getOrderId(), event.getItems());
    }
    
    private static String processOrder(Order order) {
        return "ORDER-" + order.getId();
    }
}
```

**Lambda Configuration:**
```
Handler: com.example.OrderProcessor::HANDLER
Runtime: java17
```

### Alternative: Inline Wrapper

```java
package com.example;

public class OrderProcessor {
    
    public static void handleRequest(InputStream input, 
                                     OutputStream output, 
                                     Context context) throws IOException {
        DurableExecution.wrap(
            OrderProcessor::handleOrder,
            OrderEvent.class,
            OrderResult.class
        ).handleRequest(input, output, context);
    }
    
    private static OrderResult handleOrder(OrderEvent event, DurableContext ctx) {
        // Handler logic
    }
}
```

**Lambda Configuration:**
```
Handler: com.example.OrderProcessor::handleRequest
Runtime: java17
```

### Advantages

✅ **Functional Style** - Use static methods or lambdas  
✅ **Flexible** - Can wrap any BiFunction  
✅ **No Inheritance** - Avoid base class if needed  
✅ **Testable** - Easy to test handler function directly  

### Disadvantages

⚠️ **Type Erasure** - Must explicitly pass `Class<I>` and `Class<O>`  
⚠️ **More Verbose** - Requires wrapper boilerplate  
⚠️ **Less Type Safety** - Easy to pass wrong class types  

### When to Use

- When you prefer functional programming style
- When you can't extend base class (rare)
- When you want to test handler function without Lambda infrastructure
- When you have multiple handlers in the same class

## 3. Comparison

| Aspect | Base Class | Wrapper Function |
|--------|------------|------------------|
| **Type Safety** | ✅ Strong (from generics) | ⚠️ Weaker (manual Class params) |
| **Boilerplate** | ✅ Minimal | ⚠️ More verbose |
| **Style** | Object-oriented | Functional |
| **Testing** | Test via instance | Test function directly |
| **Lambda Config** | `Class::handleRequest` | `Class::HANDLER` or `Class::handleRequest` |
| **Type Erasure** | ✅ Avoided | ⚠️ Must handle manually |
| **IDE Support** | ✅ Better | ⚠️ Good |

## 4. Implementation Details

### Base Class Implementation

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
        DurableExecutionEnvelope envelope = parseEnvelope(inputStream);
        
        // 2. Create DurableContext
        DurableContext durableContext = createContext(envelope, lambdaContext);
        
        // 3. Deserialize input
        I input = deserializeInput(envelope.getPayload());
        
        // 4. Call user handler
        O output = handleRequest(input, durableContext);
        
        // 5. Handle result (suspend or complete)
        handleResult(output, durableContext, outputStream);
    }
    
    public abstract O handleRequest(I input, DurableContext context);
    
    // Helper methods use reflection to get actual type parameters
    private I deserializeInput(String payload) {
        Class<I> inputType = getInputType();
        return serDes.deserialize(payload, inputType);
    }
    
    @SuppressWarnings("unchecked")
    private Class<I> getInputType() {
        Type superclass = getClass().getGenericSuperclass();
        ParameterizedType parameterized = (ParameterizedType) superclass;
        return (Class<I>) parameterized.getActualTypeArguments()[0];
    }
    
    @SuppressWarnings("unchecked")
    private Class<O> getOutputType() {
        Type superclass = getClass().getGenericSuperclass();
        ParameterizedType parameterized = (ParameterizedType) superclass;
        return (Class<O>) parameterized.getActualTypeArguments()[1];
    }
}
```

### Wrapper Implementation

```java
public final class DurableExecution {
    
    public static <I, O> RequestStreamHandler wrap(
        BiFunction<I, DurableContext, O> handler,
        Class<I> inputType,
        Class<O> outputType
    ) {
        return wrap(handler, inputType, outputType, new JacksonSerDes());
    }
    
    public static <I, O> RequestStreamHandler wrap(
        BiFunction<I, DurableContext, O> handler,
        Class<I> inputType,
        Class<O> outputType,
        SerDes serDes
    ) {
        return new RequestStreamHandler() {
            @Override
            public void handleRequest(InputStream inputStream, 
                                     OutputStream outputStream, 
                                     Context lambdaContext) throws IOException {
                // 1. Parse envelope
                DurableExecutionEnvelope envelope = parseEnvelope(inputStream);
                
                // 2. Create DurableContext
                DurableContext durableContext = createContext(envelope, lambdaContext);
                
                // 3. Deserialize input
                I input = serDes.deserialize(envelope.getPayload(), inputType);
                
                // 4. Call user handler
                O output = handler.apply(input, durableContext);
                
                // 5. Handle result (suspend or complete)
                handleResult(output, durableContext, outputStream, outputType);
            }
        };
    }
    
    public static <I> RequestStreamHandler wrapVoid(
        BiConsumer<I, DurableContext> handler,
        Class<I> inputType
    ) {
        return wrap((input, ctx) -> {
            handler.accept(input, ctx);
            return null;
        }, inputType, Void.class);
    }
}
```

## 5. Testing

### Testing Base Class Handler

```java
@Test
void testOrderProcessor() {
    // Create mock context
    DurableContext mockContext = mock(DurableContext.class);
    when(mockContext.step(eq("validate"), eq(Order.class), any()))
        .thenReturn(new Order("123", List.of("item1")));
    when(mockContext.step(eq("process"), eq(String.class), any()))
        .thenReturn("ORDER-123");
    
    // Test handler
    OrderProcessor handler = new OrderProcessor();
    OrderEvent event = new OrderEvent("123", List.of("item1"));
    OrderResult result = handler.handleRequest(event, mockContext);
    
    assertEquals("ORDER-123", result.getConfirmation());
    verify(mockContext).wait(Duration.ofMinutes(30));
}
```

### Testing Wrapper Function

```java
@Test
void testOrderHandler() {
    // Create mock context
    DurableContext mockContext = mock(DurableContext.class);
    when(mockContext.step(eq("validate"), eq(Order.class), any()))
        .thenReturn(new Order("123", List.of("item1")));
    when(mockContext.step(eq("process"), eq(String.class), any()))
        .thenReturn("ORDER-123");
    
    // Test handler function directly
    OrderEvent event = new OrderEvent("123", List.of("item1"));
    OrderResult result = OrderProcessor.handleOrder(event, mockContext);
    
    assertEquals("ORDER-123", result.getConfirmation());
    verify(mockContext).wait(Duration.ofMinutes(30));
}
```

## 6. Recommendation

**Use Base Class Pattern** for most cases:
- Cleaner syntax
- Better type safety
- Standard Lambda pattern
- Recommended by AWS

**Use Wrapper Function** when:
- You prefer functional style
- You need multiple handlers in one class
- You want to test handler logic without Lambda infrastructure

## 7. Migration Between Patterns

### From Base Class to Wrapper

```java
// Before: Base class
public class MyHandler extends DurableHandler<MyEvent, String> {
    @Override
    public String handleRequest(MyEvent event, DurableContext ctx) {
        return process(event, ctx);
    }
}

// After: Wrapper
public class MyHandler {
    public static final RequestStreamHandler HANDLER = 
        DurableExecution.wrap(MyHandler::handleRequest, MyEvent.class, String.class);
    
    public static String handleRequest(MyEvent event, DurableContext ctx) {
        return process(event, ctx);
    }
}
```

### From Wrapper to Base Class

```java
// Before: Wrapper
public class MyHandler {
    public static final RequestStreamHandler HANDLER = 
        DurableExecution.wrap(MyHandler::handleRequest, MyEvent.class, String.class);
    
    public static String handleRequest(MyEvent event, DurableContext ctx) {
        return process(event, ctx);
    }
}

// After: Base class
public class MyHandler extends DurableHandler<MyEvent, String> {
    @Override
    public String handleRequest(MyEvent event, DurableContext ctx) {
        return process(event, ctx);
    }
}
```

## 8. Summary

Both patterns are fully supported and provide identical functionality. The base class pattern is recommended for most use cases due to better type safety and cleaner syntax. The wrapper function pattern is available as an alternative for functional programming style or special use cases.

Choose the pattern that best fits your team's coding style and preferences.
