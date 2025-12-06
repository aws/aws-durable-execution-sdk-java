# Revised Incremental Plan - Lambda Durable Execution Java SDK

**Date:** December 7, 2025  
**Status:** Active - Starting from Increment 17  
**Completed:** Increments 1-16 ✅

---

## 🎯 What Changed

**Original Plan Issues:**
- ❌ In-memory simulation doesn't match real Lambda behavior
- ❌ Retry loops instead of Lambda's suspend/reinvoke pattern
- ❌ No envelope parsing or API integration
- ❌ Fake batching instead of real checkpoint API

**New Approach:**
- ✅ Abstract Lambda API behind `DurableExecutionClient` interface
- ✅ Two implementations: `InMemoryClient` (testing) + `LambdaClient` (production)
- ✅ Remove retry loops - Lambda handles retries via reinvocation
- ✅ Parse envelope format from CONTRACT.md
- ✅ Proper checkpoint operations (START, SUCCEED, FAIL actions)
- ✅ Keep incremental approach - test locally, deploy when ready

---

## 📋 Completed (Increments 1-16)

- [x] Project setup, DurableContext interface
- [x] Basic step execution with operation counter
- [x] Operation model and checkpoint log
- [x] Basic replay logic
- [x] Jackson serialization (SerDes)
- [x] Wait operation
- [x] DurableFuture and async execution
- [x] Simple RetryPolicy class
- [x] Retry logic in step() method

**Current State:** ~250 LOC, 15 tests passing

---

## 🔄 Part 3 (Revised): Lambda API Integration

### 🎯 Increment 17: DurableExecutionClient Interface (30 min)

**Goal:** Abstract Lambda API for testability.

#### Step 17.1: Create client interface
```java
package com.amazonaws.lambda.durable.client;

import com.amazonaws.lambda.durable.model.Operation;
import com.amazonaws.lambda.durable.model.OperationUpdate;
import java.util.List;

/**
 * Interface for Lambda Durable Execution API operations.
 * Allows testing with in-memory implementation and production with real Lambda client.
 */
public interface DurableExecutionClient {
    
    /**
     * Get execution state (checkpoint log).
     * Handles pagination automatically.
     * 
     * @param durableExecutionArn The execution ARN
     * @param checkpointToken The checkpoint token
     * @return List of all operations in execution state
     */
    List<Operation> getExecutionState(String durableExecutionArn, String checkpointToken);
    
    /**
     * Checkpoint operation updates.
     * Returns new checkpoint token.
     * 
     * @param durableExecutionArn The execution ARN
     * @param checkpointToken The current checkpoint token
     * @param updates List of operation updates
     * @return New checkpoint token
     */
    String checkpoint(String durableExecutionArn, String checkpointToken, List<OperationUpdate> updates);
}
```

#### Step 17.2: Create OperationUpdate model
```java
package com.amazonaws.lambda.durable.model;

/**
 * Represents an update to checkpoint for an operation.
 * Maps to CONTRACT.md OperationUpdate structure.
 */
public class OperationUpdate {
    private final String id;
    private final String type;  // STEP, WAIT, CALLBACK, etc.
    private final String action;  // START, SUCCEED, FAIL
    private final String name;
    private final String payload;  // Result or error payload
    
    public OperationUpdate(String id, String type, String action, String name, String payload) {
        this.id = id;
        this.type = type;
        this.action = action;
        this.name = name;
        this.payload = payload;
    }
    
    // Getters
    public String getId() { return id; }
    public String getType() { return type; }
    public String getAction() { return action; }
    public String getName() { return name; }
    public String getPayload() { return payload; }
}
```

#### ✅ Verify
```bash
mvn clean compile
# Should compile successfully
```

---

### 🎯 Increment 18: InMemoryDurableExecutionClient (30 min)

**Goal:** In-memory implementation for local testing.

#### Step 18.1: Create in-memory client
```java
package com.amazonaws.lambda.durable.client;

import com.amazonaws.lambda.durable.model.Operation;
import com.amazonaws.lambda.durable.model.OperationUpdate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory implementation for local testing.
 * Simulates Lambda API behavior without actual AWS calls.
 */
public class InMemoryDurableExecutionClient implements DurableExecutionClient {
    
    private final Map<String, Operation> operations = new HashMap<>();
    private final AtomicInteger tokenCounter = new AtomicInteger(0);
    private String currentToken = "token-0";
    
    @Override
    public List<Operation> getExecutionState(String durableExecutionArn, String checkpointToken) {
        // Return all operations in order
        return new ArrayList<>(operations.values());
    }
    
    @Override
    public String checkpoint(String durableExecutionArn, String checkpointToken, List<OperationUpdate> updates) {
        // Validate token
        if (!checkpointToken.equals(currentToken)) {
            throw new IllegalArgumentException("Invalid checkpoint token");
        }
        
        // Apply updates
        for (OperationUpdate update : updates) {
            applyUpdate(update);
        }
        
        // Generate new token
        currentToken = "token-" + tokenCounter.incrementAndGet();
        return currentToken;
    }
    
    private void applyUpdate(OperationUpdate update) {
        String id = update.getId();
        String action = update.getAction();
        
        if ("START".equals(action)) {
            // Create new operation
            operations.put(id, new Operation(
                id,
                update.getName(),
                null,  // No result yet
                "STARTED"
            ));
        } else if ("SUCCEED".equals(action)) {
            // Update to succeeded
            Operation existing = operations.get(id);
            if (existing != null) {
                operations.put(id, new Operation(
                    id,
                    existing.getName(),
                    update.getPayload(),
                    "SUCCEEDED"
                ));
            }
        } else if ("FAIL".equals(action)) {
            // Update to failed
            Operation existing = operations.get(id);
            if (existing != null) {
                operations.put(id, new Operation(
                    id,
                    existing.getName(),
                    update.getPayload(),
                    "FAILED"
                ));
            }
        }
    }
    
    public String getCurrentToken() {
        return currentToken;
    }
}
```

#### Step 18.2: Update Operation model to include status
```java
package com.amazonaws.lambda.durable.model;

public class Operation {
    private final String id;
    private final String name;
    private final String result;
    private final String status;  // NEW: STARTED, SUCCEEDED, FAILED
    
    public Operation(String id, String name, String result, String status) {
        this.id = id;
        this.name = name;
        this.result = result;
        this.status = status;
    }
    
    public String getId() { return id; }
    public String getName() { return name; }
    public String getResult() { return result; }
    public String getStatus() { return status; }
    
    public boolean isSucceeded() {
        return "SUCCEEDED".equals(status);
    }
    
    public boolean isFailed() {
        return "FAILED".equals(status);
    }
}
```

#### Step 18.3: Test the client
```java
package com.amazonaws.lambda.durable.client;

import com.amazonaws.lambda.durable.model.OperationUpdate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryDurableExecutionClientTest {
    
    @Test
    void testCheckpointAndRetrieve() {
        var client = new InMemoryDurableExecutionClient();
        var token = client.getCurrentToken();
        
        // Checkpoint a step start
        var updates = List.of(
            new OperationUpdate("0", "STEP", "START", "test-step", null)
        );
        var newToken = client.checkpoint("arn:test", token, updates);
        
        // Verify new token
        assertNotEquals(token, newToken);
        
        // Get state
        var operations = client.getExecutionState("arn:test", newToken);
        assertEquals(1, operations.size());
        assertEquals("test-step", operations.get(0).getName());
        assertEquals("STARTED", operations.get(0).getStatus());
    }
    
    @Test
    void testCheckpointSucceed() {
        var client = new InMemoryDurableExecutionClient();
        var token = client.getCurrentToken();
        
        // Start operation
        token = client.checkpoint("arn:test", token, List.of(
            new OperationUpdate("0", "STEP", "START", "test", null)
        ));
        
        // Complete operation
        token = client.checkpoint("arn:test", token, List.of(
            new OperationUpdate("0", "STEP", "SUCCEED", "test", "{\"result\":\"success\"}")
        ));
        
        // Verify
        var operations = client.getExecutionState("arn:test", token);
        assertEquals(1, operations.size());
        assertTrue(operations.get(0).isSucceeded());
        assertEquals("{\"result\":\"success\"}", operations.get(0).getResult());
    }
}
```

#### ✅ Verify
```bash
mvn test
# Should have 17 tests passing
```

---

### 🎯 Increment 19: Refactor DurableContext to Use Client (45 min)

**Goal:** Replace in-memory checkpoint log with client abstraction.

#### Step 19.1: Update DurableContext constructor
```java
public class DurableContext {
    
    private int operationCounter = 0;
    private final String durableExecutionArn;
    private String checkpointToken;
    private final DurableExecutionClient client;
    private final SerDes serDes;
    private final ExecutorService executor;
    private final Map<String, Operation> operationCache;  // For replay lookup
    
    /**
     * Create context for testing (uses in-memory client).
     */
    public DurableContext() {
        this("arn:test:execution", new InMemoryDurableExecutionClient(), new JacksonSerDes());
    }
    
    /**
     * Create context with specific client (for production or testing).
     */
    public DurableContext(String durableExecutionArn, DurableExecutionClient client, SerDes serDes) {
        this.durableExecutionArn = durableExecutionArn;
        this.client = client;
        this.serDes = serDes;
        this.executor = createDefaultExecutor();
        
        // Get initial state from client
        this.checkpointToken = (client instanceof InMemoryDurableExecutionClient) 
            ? ((InMemoryDurableExecutionClient) client).getCurrentToken()
            : "initial-token";
        
        // Load operations into cache for replay
        var operations = client.getExecutionState(durableExecutionArn, checkpointToken);
        this.operationCache = new HashMap<>();
        for (var op : operations) {
            operationCache.put(op.getId(), op);
        }
    }
    
    private static ExecutorService createDefaultExecutor() {
        var processors = Runtime.getRuntime().availableProcessors();
        var poolSize = Math.max(2, Math.min(processors, 10));
        return Executors.newFixedThreadPool(poolSize);
    }
}
```

#### Step 19.2: Update step() to use client
```java
public <T> T step(String name, Class<T> type, Callable<T> action) {
    return step(name, type, action, RetryPolicy.defaultPolicy());
}

public <T> T step(String name, Class<T> type, Callable<T> action, RetryPolicy policy) {
    var opId = String.valueOf(operationCounter++);
    
    // Check replay - is operation already completed?
    var existing = operationCache.get(opId);
    if (existing != null && existing.isSucceeded()) {
        System.out.println("REPLAY: Skipping step: " + name + " (id=" + opId + ")");
        return serDes.deserialize(existing.getResult(), type);
    }
    
    // Execute step
    try {
        System.out.println("Executing step: " + name + " (id=" + opId + ")");
        
        // Checkpoint START
        checkpointToken = client.checkpoint(durableExecutionArn, checkpointToken, List.of(
            new OperationUpdate(opId, "STEP", "START", name, null)
        ));
        
        // Execute action
        T result = action.call();
        
        // Checkpoint SUCCEED
        var resultJson = serDes.serialize(result);
        checkpointToken = client.checkpoint(durableExecutionArn, checkpointToken, List.of(
            new OperationUpdate(opId, "STEP", "SUCCEED", name, resultJson)
        ));
        
        // Update cache
        operationCache.put(opId, new Operation(opId, name, resultJson, "SUCCEEDED"));
        
        System.out.println("Step completed: " + name + " -> " + result);
        return result;
        
    } catch (Exception e) {
        // Checkpoint FAIL - let Lambda handle retry via reinvocation
        try {
            var errorJson = serDes.serialize(Map.of(
                "errorType", e.getClass().getSimpleName(),
                "errorMessage", e.getMessage()
            ));
            checkpointToken = client.checkpoint(durableExecutionArn, checkpointToken, List.of(
                new OperationUpdate(opId, "STEP", "FAIL", name, errorJson)
            ));
        } catch (Exception checkpointError) {
            System.err.println("Failed to checkpoint error: " + checkpointError.getMessage());
        }
        
        // Rethrow - Lambda will reinvoke after backoff
        throw new RuntimeException("Step failed: " + name, e);
    }
}
```

#### Step 19.3: Remove retry loop from stepAsync()
```java
public <T> DurableFuture<T> stepAsync(String name, Class<T> type, Callable<T> action, RetryPolicy policy) {
    var opId = operationCounter++;
    
    // Check replay
    var existing = operationCache.get(String.valueOf(opId));
    if (existing != null && existing.isSucceeded()) {
        System.out.println("REPLAY: Skipping step: " + name + " (id=" + opId + ")");
        var result = serDes.deserialize(existing.getResult(), type);
        return DurableFuture.completed(result);
    }
    
    // Execute async (no retry loop - Lambda handles it)
    var future = CompletableFuture.supplyAsync(() -> {
        return step(name, type, action, policy);  // Delegate to sync step()
    }, executor);
    
    return new DurableFuture<>(future);
}
```

#### Step 19.4: Update tests
Update existing tests to work with new client-based approach. Tests should still pass with in-memory client.

#### ✅ Verify
```bash
mvn test
# All tests should still pass (now using client abstraction)
```

---

### 🎯 Increment 20: Remove Retry Loops (20 min)

**Goal:** Remove in-SDK retry logic - Lambda handles it.

#### Step 20.1: Simplify RetryPolicy
```java
package com.amazonaws.lambda.durable.config;

/**
 * Retry policy configuration.
 * Note: Actual retry is handled by Lambda service via reinvocation.
 * This class is for future extensibility (e.g., custom retry strategies).
 */
public class RetryPolicy {
    
    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy();
    }
    
    // Future: Add fields for custom retry behavior if needed
}
```

#### Step 20.2: Remove retry loop from step()
Already done in Increment 19 - step() now just executes once and checkpoints FAIL on error.

#### Step 20.3: Update tests
Remove tests that verify retry loops. Add tests that verify FAIL checkpoint on error.

```java
@Test
void testStepFailureCheckpoint() {
    var client = new InMemoryDurableExecutionClient();
    var ctx = new DurableContext("arn:test", client, new JacksonSerDes());
    
    // Step that fails
    assertThrows(RuntimeException.class, () -> {
        ctx.step("failing-step", String.class, () -> {
            throw new RuntimeException("Test failure");
        });
    });
    
    // Verify FAIL was checkpointed
    var operations = client.getExecutionState("arn:test", client.getCurrentToken());
    var failedOp = operations.stream()
        .filter(op -> "failing-step".equals(op.getName()))
        .findFirst()
        .orElseThrow();
    
    assertTrue(failedOp.isFailed());
}
```

#### ✅ Verify
```bash
mvn test
# Tests should pass with simplified retry logic
```

---

### 🎯 Increment 21: Envelope Parsing (45 min)

**Goal:** Parse Lambda durable execution input envelope.

#### Step 21.1: Create envelope models
```java
package com.amazonaws.lambda.durable.model;

import java.util.List;

/**
 * Input envelope from Lambda durable execution.
 * Maps to CONTRACT.md input format.
 */
public class DurableExecutionEnvelope {
    private String durableExecutionArn;
    private String checkpointToken;
    private InitialExecutionState initialExecutionState;
    
    // Getters and setters for Jackson
    public String getDurableExecutionArn() { return durableExecutionArn; }
    public void setDurableExecutionArn(String arn) { this.durableExecutionArn = arn; }
    
    public String getCheckpointToken() { return checkpointToken; }
    public void setCheckpointToken(String token) { this.checkpointToken = token; }
    
    public InitialExecutionState getInitialExecutionState() { return initialExecutionState; }
    public void setInitialExecutionState(InitialExecutionState state) { this.initialExecutionState = state; }
    
    public static class InitialExecutionState {
        private List<Operation> operations;
        private String nextMarker;
        
        public List<Operation> getOperations() { return operations; }
        public void setOperations(List<Operation> ops) { this.operations = ops; }
        
        public String getNextMarker() { return nextMarker; }
        public void setNextMarker(String marker) { this.nextMarker = marker; }
    }
}
```

#### Step 21.2: Create envelope parser
```java
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.model.DurableExecutionEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;

/**
 * Parses Lambda durable execution input envelope.
 */
public class EnvelopeParser {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public DurableExecutionEnvelope parse(InputStream input) throws Exception {
        return objectMapper.readValue(input, DurableExecutionEnvelope.class);
    }
    
    public DurableExecutionEnvelope parse(String json) throws Exception {
        return objectMapper.readValue(json, DurableExecutionEnvelope.class);
    }
}
```

#### Step 21.3: Test envelope parsing
```java
package com.amazonaws.lambda.durable;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnvelopeParserTest {
    
    @Test
    void testParseEnvelope() throws Exception {
        var json = """
            {
                "DurableExecutionArn": "arn:aws:lambda:us-east-1:123:execution:test",
                "CheckpointToken": "token-123",
                "InitialExecutionState": {
                    "Operations": [],
                    "NextMarker": null
                }
            }
            """;
        
        var parser = new EnvelopeParser();
        var envelope = parser.parse(json);
        
        assertEquals("arn:aws:lambda:us-east-1:123:execution:test", envelope.getDurableExecutionArn());
        assertEquals("token-123", envelope.getCheckpointToken());
        assertNotNull(envelope.getInitialExecutionState());
    }
}
```

#### ✅ Verify
```bash
mvn test
# Envelope parsing test should pass
```

---

### 🎯 Increment 22: DurableHandler Base Class (45 min)

**Goal:** Create base class that handles envelope parsing and output format.

#### Step 22.1: Add Lambda dependency
```xml
<dependency>
    <groupId>com.amazonaws</groupId>
    <artifactId>aws-lambda-java-core</artifactId>
    <version>1.2.3</version>
</dependency>
```

#### Step 22.2: Create DurableHandler
```java
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import com.amazonaws.lambda.durable.client.InMemoryDurableExecutionClient;
import com.amazonaws.lambda.durable.model.DurableExecutionEnvelope;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Base class for durable Lambda handlers.
 * Handles envelope parsing and output format per CONTRACT.md.
 */
public abstract class DurableHandler<I, O> implements RequestStreamHandler {
    
    private final EnvelopeParser envelopeParser = new EnvelopeParser();
    private final JacksonSerDes serDes = new JacksonSerDes();
    
    @Override
    public void handleRequest(InputStream input, OutputStream output, Context lambdaContext) {
        try {
            // Parse envelope
            var envelope = envelopeParser.parse(input);
            
            // Create client (override for production)
            var client = createClient();
            
            // Create durable context
            var durableContext = new DurableContext(
                envelope.getDurableExecutionArn(),
                client,
                serDes
            );
            
            // Load initial state
            durableContext.loadInitialState(
                envelope.getCheckpointToken(),
                envelope.getInitialExecutionState().getOperations()
            );
            
            // Call user handler
            O result = handleRequest(durableContext);
            
            // Write success response per CONTRACT.md
            writeResponse(output, "SUCCEEDED", serDes.serialize(result), null);
            
        } catch (Exception e) {
            // Write failure response per CONTRACT.md
            try {
                var error = Map.of(
                    "ErrorType", e.getClass().getSimpleName(),
                    "ErrorMessage", e.getMessage()
                );
                writeResponse(output, "FAILED", null, error);
            } catch (Exception writeError) {
                throw new RuntimeException("Failed to write error response", writeError);
            }
        }
    }
    
    /**
     * Override to provide production Lambda client.
     * Default uses in-memory client for testing.
     */
    protected DurableExecutionClient createClient() {
        return new InMemoryDurableExecutionClient();
    }
    
    /**
     * User implements this method with durable logic.
     */
    protected abstract O handleRequest(DurableContext context) throws Exception;
    
    private void writeResponse(OutputStream output, String status, String result, Object error) throws Exception {
        var response = new java.util.HashMap<String, Object>();
        response.put("Status", status);
        if (result != null) {
            response.put("Result", result);
        }
        if (error != null) {
            response.put("Error", error);
        }
        
        var json = serDes.serialize(response);
        var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        writer.write(json);
        writer.flush();
    }
}
```

#### Step 22.3: Update DurableContext to support loading initial state
```java
public void loadInitialState(String token, List<Operation> operations) {
    this.checkpointToken = token;
    this.operationCache.clear();
    for (var op : operations) {
        operationCache.put(op.getId(), op);
    }
}
```

#### Step 22.4: Create example handler
```java
package com.amazonaws.lambda.durable.examples;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableHandler;

public class SimpleHandler extends DurableHandler<String, String> {
    
    @Override
    protected String handleRequest(DurableContext context) throws Exception {
        var result = context.step("process", String.class, () -> 
            "Processed: " + System.currentTimeMillis()
        );
        return result;
    }
}
```

#### ✅ Verify
```bash
mvn test
# All tests should pass
```

---

## 📊 Progress Summary

**After Increment 22:**
- ✅ Lambda API abstraction (DurableExecutionClient)
- ✅ In-memory client for testing
- ✅ Envelope parsing
- ✅ DurableHandler base class
- ✅ Proper checkpoint operations (START, SUCCEED, FAIL)
- ✅ No retry loops (Lambda handles it)
- ✅ ~400 LOC

**Remaining:**
- [ ] LambdaDurableExecutionClient (real AWS SDK integration)
- [ ] Checkpoint batching optimization
- [ ] Wait operation updates
- [ ] Documentation and examples

---

## 🎯 Next Steps

Continue with Part 4 for:
- Real Lambda client implementation
- Checkpoint batching
- Production deployment
- Documentation

**Status:** Ready to continue from Increment 17
