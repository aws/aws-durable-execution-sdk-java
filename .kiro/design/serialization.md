# Serialization Design

**Date:** December 6, 2025  
**Status:** Active Design - MVP

## Overview

This document details how serialization works in the SDK, focusing on the MVP implementation using Jackson.

## 1. SerDes Interface

### Core Interface

```java
package com.amazonaws.lambda.durable.serde;

/**
 * Interface for serializing and deserializing durable operation data.
 */
public interface SerDes {
    
    /**
     * Serialize an object to JSON string.
     * 
     * @param value the object to serialize
     * @return JSON string representation
     * @throws SerDesException if serialization fails
     */
    String serialize(Object value) throws SerDesException;
    
    /**
     * Deserialize a JSON string to an object of the specified type.
     * 
     * @param json the JSON string
     * @param type the target type
     * @return the deserialized object
     * @throws SerDesException if deserialization fails
     */
    <T> T deserialize(String json, Class<T> type) throws SerDesException;
}
```

### Exception

```java
package com.amazonaws.lambda.durable.serde;

/**
 * Exception thrown when serialization or deserialization fails.
 */
public class SerDesException extends RuntimeException {
    public SerDesException(String message) {
        super(message);
    }
    
    public SerDesException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

## 2. Jackson Implementation (Default)

### JacksonSerDes

```java
package com.amazonaws.lambda.durable.serde;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Default SerDes implementation using Jackson.
 */
public class JacksonSerDes implements SerDes {
    
    private final ObjectMapper mapper;
    
    public JacksonSerDes() {
        this(createDefaultObjectMapper());
    }
    
    public JacksonSerDes(ObjectMapper mapper) {
        this.mapper = mapper;
    }
    
    @Override
    public String serialize(Object value) throws SerDesException {
        if (value == null) {
            return null;
        }
        
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new SerDesException("Failed to serialize: " + value.getClass().getName(), e);
        }
    }
    
    @Override
    public <T> T deserialize(String json, Class<T> type) throws SerDesException {
        if (json == null) {
            return null;
        }
        
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new SerDesException("Failed to deserialize to: " + type.getName(), e);
        }
    }
    
    /**
     * Create default ObjectMapper with sensible defaults.
     */
    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        
        // Register Java 8 time support
        mapper.registerModule(new JavaTimeModule());
        
        // Write dates as ISO-8601 strings, not timestamps
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        
        // Don't fail on unknown properties (forward compatibility)
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        // Pretty print for debugging (can disable for production)
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        
        return mapper;
    }
}
```

## 3. Usage in SDK

### Step Result Serialization

```java
// In step execution
public <T> T step(String name, Class<T> type, Callable<T> action) {
    // Execute action
    T result = action.call();
    
    // Serialize result for checkpoint
    String payload = serDes.serialize(result);
    
    // Checkpoint
    checkpointSuccess(operationId, name, payload);
    
    return result;
}
```

### Step Result Deserialization (Replay)

```java
// During replay
Operation existingOp = executionState.getOperation(operationId);
if (existingOp.getStatus() == OperationStatus.SUCCEEDED) {
    // Deserialize cached result
    String payload = existingOp.getStepDetails().getResult();
    T result = serDes.deserialize(payload, type);
    return result;
}
```

### User Input Deserialization

```java
// In DurableHandler
Operation executionOp = executionState.getOperation("0");
String inputPayload = executionOp.getExecutionDetails().getInputPayload();

// Deserialize to user's input type
I userInput = serDes.deserialize(inputPayload, getInputType());
```

### User Output Serialization

```java
// In DurableHandler
O result = handleRequest(userInput, durableContext);

// Serialize result
String outputPayload = serDes.serialize(result);

// Checkpoint execution completion
checkpointExecutionSuccess(outputPayload);
```

## 4. Error Serialization

### Error Object Structure

```java
public class ErrorObject {
    private String errorType;      // Exception class name
    private String errorMessage;   // Exception message
    private String stackTrace;     // Serialized stack trace
    private String errorData;      // Serialized exception (optional)
}
```

### Serialize Exception

```java
public ErrorObject serializeError(Throwable error) {
    return ErrorObject.builder()
        .errorType(error.getClass().getName())
        .errorMessage(error.getMessage())
        .stackTrace(serializeStackTrace(error.getStackTrace()))
        .errorData(serializeErrorData(error))
        .build();
}

private String serializeStackTrace(StackTraceElement[] stackTrace) {
    StringBuilder sb = new StringBuilder();
    for (StackTraceElement element : stackTrace) {
        sb.append(element.toString()).append("\n");
    }
    return sb.toString();
}

private String serializeErrorData(Throwable error) {
    try {
        // Try to serialize the exception itself
        return serDes.serialize(error);
    } catch (Exception e) {
        // If serialization fails, just return null
        // We still have errorType and errorMessage
        return null;
    }
}
```

### Deserialize Exception

```java
public Throwable deserializeError(ErrorObject errorObj) {
    // Try to deserialize the full exception
    if (errorObj.getErrorData() != null) {
        try {
            Class<?> errorClass = Class.forName(errorObj.getErrorType());
            return (Throwable) serDes.deserialize(errorObj.getErrorData(), errorClass);
        } catch (Exception e) {
            // Fall through to generic exception
        }
    }
    
    // Create generic exception with preserved info
    StepFailedException exception = new StepFailedException(
        errorObj.getErrorType() + ": " + errorObj.getErrorMessage()
    );
    
    // Restore stack trace
    exception.setStackTrace(deserializeStackTrace(errorObj.getStackTrace()));
    
    return exception;
}

private StackTraceElement[] deserializeStackTrace(String stackTrace) {
    // Parse stack trace string back to StackTraceElement[]
    // Implementation details...
}
```

## 5. Type Handling

### Why Class<T> Parameter?

**Problem:** Java type erasure

```java
// This doesn't work - type is erased at runtime
public <T> T step(String name, Callable<T> action) {
    T result = action.call();
    // How do we deserialize? We don't know T at runtime!
}
```

**Solution:** Explicit Class<T> parameter

```java
// This works - we have the type at runtime
public <T> T step(String name, Class<T> type, Callable<T> action) {
    T result = action.call();
    String json = serDes.serialize(result);
    // Later, during replay:
    T cached = serDes.deserialize(json, type); // We know the type!
}
```

### Primitive Types

```java
// Primitives must use wrapper classes
ctx.step("count", Integer.class, () -> 42);        // ✅ Works
ctx.step("count", int.class, () -> 42);            // ❌ Don't use primitives

ctx.step("flag", Boolean.class, () -> true);       // ✅ Works
ctx.step("amount", Double.class, () -> 99.99);     // ✅ Works
```

### Collections

```java
// Simple collections work
ctx.step("list", List.class, () -> List.of("a", "b", "c"));

// But type information is lost
List result = ctx.step("list", List.class, () -> ...);
// result is List<Object>, not List<String>

// For type-safe collections, use custom classes
public class StringList {
    private List<String> items;
    // getters/setters
}

StringList result = ctx.step("list", StringList.class, () -> 
    new StringList(List.of("a", "b", "c"))
);
```

### Generic Types

```java
// Generic types require wrapper classes
public class Result<T> {
    private T value;
    // getters/setters
}

// This loses type information
Result result = ctx.step("generic", Result.class, () -> new Result<>("value"));

// Better: Use concrete types
public class StringResult {
    private String value;
}

StringResult result = ctx.step("result", StringResult.class, () -> 
    new StringResult("value")
);
```

## 6. Custom SerDes

### Use Case: Custom Serialization Library

```java
public class GsonSerDes implements SerDes {
    private final Gson gson;
    
    public GsonSerDes() {
        this.gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .create();
    }
    
    @Override
    public String serialize(Object value) throws SerDesException {
        try {
            return gson.toJson(value);
        } catch (Exception e) {
            throw new SerDesException("Gson serialization failed", e);
        }
    }
    
    @Override
    public <T> T deserialize(String json, Class<T> type) throws SerDesException {
        try {
            return gson.fromJson(json, type);
        } catch (Exception e) {
            throw new SerDesException("Gson deserialization failed", e);
        }
    }
}
```

### Configure Custom SerDes

```java
public class MyHandler extends DurableHandler<MyEvent, String> {
    
    @Override
    protected SerDes createSerDes() {
        return new GsonSerDes();
    }
    
    @Override
    public String handleRequest(MyEvent event, DurableContext ctx) {
        // Uses GsonSerDes for all serialization
        return ctx.step("process", String.class, () -> process(event));
    }
}
```

## 7. Null Handling

### Null Values

```java
// Null input
String result = ctx.step("process", String.class, () -> null);
// Serializes to: null
// Deserializes to: null

// Null in objects
public class Data {
    private String value; // null
}
Data data = ctx.step("get", Data.class, () -> new Data());
// Serializes to: {"value":null}
```

### Void Return Type

```java
// For operations with no return value
ctx.step("notify", Void.class, () -> {
    sendNotification();
    return null;
});
```

## 8. Performance Considerations

### Serialization Cost

```java
// Small objects: ~1ms
String result = ctx.step("simple", String.class, () -> "hello");

// Large objects: ~10-100ms
LargeObject result = ctx.step("large", LargeObject.class, () -> 
    new LargeObject(/* lots of data */)
);
```

**Recommendation:** Keep step results small. If you need to pass large data, use references (S3 keys, database IDs).

### Caching ObjectMapper

```java
// ✅ Good - reuse ObjectMapper
private static final ObjectMapper MAPPER = new ObjectMapper();

// ❌ Bad - creates new ObjectMapper every time
public String serialize(Object value) {
    ObjectMapper mapper = new ObjectMapper(); // Expensive!
    return mapper.writeValueAsString(value);
}
```

## 9. Common Patterns

### Pattern 1: Simple Types

```java
// Primitives and strings
String name = ctx.step("getName", String.class, () -> "Alice");
Integer count = ctx.step("getCount", Integer.class, () -> 42);
Boolean flag = ctx.step("getFlag", Boolean.class, () -> true);
```

### Pattern 2: POJOs

```java
public class User {
    private String name;
    private int age;
    // getters/setters or record
}

User user = ctx.step("getUser", User.class, () -> 
    new User("Alice", 30)
);
```

### Pattern 3: Collections with Wrapper

```java
public class UserList {
    private List<User> users;
    // getters/setters
}

UserList users = ctx.step("getUsers", UserList.class, () -> 
    new UserList(fetchUsers())
);
```

### Pattern 4: Enums

```java
public enum Status {
    PENDING, APPROVED, REJECTED
}

Status status = ctx.step("getStatus", Status.class, () -> 
    Status.APPROVED
);
```

## 10. Error Scenarios

### Scenario 1: Serialization Fails

```java
// Object can't be serialized (e.g., contains non-serializable field)
try {
    ctx.step("bad", BadClass.class, () -> new BadClass());
} catch (SerDesException e) {
    // Handle serialization failure
    // This is a permanent failure - won't succeed on retry
}
```

**Solution:** Ensure all step result types are serializable.

### Scenario 2: Deserialization Fails (Schema Change)

```java
// Version 1: User has 2 fields
public class User {
    private String name;
    private int age;
}

// Version 2: User has 3 fields
public class User {
    private String name;
    private int age;
    private String email; // New field
}

// Replay with old data
User user = ctx.step("getUser", User.class, () -> ...);
// Deserializes successfully: email will be null
```

**Solution:** Jackson ignores unknown properties by default. New fields should have sensible defaults.

### Scenario 3: Type Mismatch

```java
// Stored as String
ctx.step("value", String.class, () -> "42");

// Later, try to deserialize as Integer
Integer value = ctx.step("value", Integer.class, () -> ...);
// Throws SerDesException during replay
```

**Solution:** Don't change step result types between versions.

## 11. Testing

### Test Serialization Round-Trip

```java
@Test
void testSerializationRoundTrip() {
    SerDes serDes = new JacksonSerDes();
    
    User original = new User("Alice", 30);
    
    // Serialize
    String json = serDes.serialize(original);
    
    // Deserialize
    User deserialized = serDes.deserialize(json, User.class);
    
    // Verify
    assertEquals(original.getName(), deserialized.getName());
    assertEquals(original.getAge(), deserialized.getAge());
}
```

### Test Null Handling

```java
@Test
void testNullSerialization() {
    SerDes serDes = new JacksonSerDes();
    
    String json = serDes.serialize(null);
    assertNull(json);
    
    String result = serDes.deserialize(null, String.class);
    assertNull(result);
}
```

### Test Error Serialization

```java
@Test
void testErrorSerialization() {
    Exception error = new RuntimeException("Test error");
    
    ErrorObject errorObj = serializeError(error);
    
    assertEquals("java.lang.RuntimeException", errorObj.getErrorType());
    assertEquals("Test error", errorObj.getErrorMessage());
    assertNotNull(errorObj.getStackTrace());
}
```

## 12. Dependencies

### Maven

```xml
<dependencies>
    <!-- Jackson Core -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.15.2</version>
    </dependency>
    
    <!-- Java 8 Time Support -->
    <dependency>
        <groupId>com.fasterxml.jackson.datatype</groupId>
        <artifactId>jackson-datatype-jsr310</artifactId>
        <version>2.15.2</version>
    </dependency>
</dependencies>
```

## 13. Best Practices

### ✅ Do

- Use simple, serializable types for step results
- Keep step results small (< 1MB)
- Use wrapper classes for primitives
- Test serialization round-trips
- Version your data classes carefully

### ❌ Don't

- Don't use primitive types (int, boolean, etc.)
- Don't serialize large objects (> 10MB)
- Don't change step result types between versions
- Don't use complex generic types
- Don't serialize non-serializable objects (streams, connections, etc.)

## 14. MVP Scope

For MVP, we only need:

✅ **JacksonSerDes** - Default implementation  
✅ **Basic types** - String, Integer, Boolean, POJOs  
✅ **Error serialization** - Exception → ErrorObject  
✅ **Null handling** - Serialize/deserialize null  

Not needed for MVP:
❌ Custom SerDes support (can add later)  
❌ Advanced type handling (generics, complex collections)  
❌ Performance optimizations  

## 15. Open Questions

1. **Payload size limits:** What's the max size for step results?
2. **Compression:** Should we compress large payloads?
3. **Schema evolution:** How to handle breaking changes?
4. **Binary formats:** Support Protocol Buffers, Avro?

## 16. Future Enhancements

### TypeReference Support (Post-MVP)

**Problem:** Class<T> loses generic type information

```java
// Current: Type information lost
List<String> list = ctx.step("list", List.class, () -> List.of("a", "b"));
// list is actually List<Object> at runtime
```

**Solution:** Add TypeReference support

```java
// Future API
<T> T step(String name, TypeReference<T> typeRef, Callable<T> action);

// Usage
List<String> list = ctx.step("list", 
    new TypeReference<List<String>>() {},
    () -> List.of("a", "b")
);

Map<String, User> map = ctx.step("map",
    new TypeReference<Map<String, User>>() {},
    () -> fetchUserMap()
);
```

**Implementation:**

```java
// SerDes interface addition
public interface SerDes {
    String serialize(Object value);
    <T> T deserialize(String json, Class<T> type);
    <T> T deserialize(String json, TypeReference<T> typeRef); // New!
}

// DurableContext overloads
<T> T step(String name, Class<T> type, Callable<T> action);
<T> T step(String name, TypeReference<T> typeRef, Callable<T> action); // New!
```

**MVP Workaround:** Use wrapper classes

```java
public class StringList {
    private List<String> items;
}

StringList result = ctx.step("list", StringList.class, () -> 
    new StringList(List.of("a", "b"))
);
```

## 17. Next Steps

- [ ] Implement JacksonSerDes
- [ ] Implement SerDesException
- [ ] Add error serialization utilities
- [ ] Write serialization tests
- [ ] Document type handling guidelines
