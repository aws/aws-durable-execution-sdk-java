# Revised Incremental Plan - Part 4: Production Features

**Continuing from Increment 22...**

---

## 🎯 Increment 23: LambdaDurableExecutionClient (1 hour)

**Goal:** Real AWS SDK integration for production deployment.

### Step 23.1: Add AWS SDK dependency
```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>lambda</artifactId>
    <version>2.20.0</version>
</dependency>
```

### Step 23.2: Create Lambda client implementation
```java
package com.amazonaws.lambda.durable.client;

import com.amazonaws.lambda.durable.model.Operation;
import com.amazonaws.lambda.durable.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Production implementation using AWS Lambda SDK.
 * Calls real CheckpointDurableExecution and GetDurableExecutionState APIs.
 */
public class LambdaDurableExecutionClient implements DurableExecutionClient {
    
    private final LambdaClient lambdaClient;
    
    public LambdaDurableExecutionClient() {
        this.lambdaClient = LambdaClient.create();
    }
    
    public LambdaDurableExecutionClient(LambdaClient lambdaClient) {
        this.lambdaClient = lambdaClient;
    }
    
    @Override
    public List<Operation> getExecutionState(String durableExecutionArn, String checkpointToken) {
        var allOperations = new ArrayList<Operation>();
        String marker = null;
        
        // Handle pagination per CONTRACT.md
        do {
            var request = GetDurableExecutionStateRequest.builder()
                .durableExecutionArn(durableExecutionArn)
                .checkpointToken(checkpointToken)
                .marker(marker)
                .build();
            
            var response = lambdaClient.getDurableExecutionState(request);
            
            // Convert AWS SDK operations to our model
            for (var awsOp : response.operations()) {
                allOperations.add(convertOperation(awsOp));
            }
            
            marker = response.nextMarker();
        } while (marker != null);
        
        return allOperations;
    }
    
    @Override
    public String checkpoint(String durableExecutionArn, String checkpointToken, List<OperationUpdate> updates) {
        // Convert our updates to AWS SDK format
        var awsUpdates = new ArrayList<software.amazon.awssdk.services.lambda.model.OperationUpdate>();
        for (var update : updates) {
            awsUpdates.add(convertOperationUpdate(update));
        }
        
        var request = CheckpointDurableExecutionRequest.builder()
            .durableExecutionArn(durableExecutionArn)
            .checkpointToken(checkpointToken)
            .operations(awsUpdates)
            .build();
        
        var response = lambdaClient.checkpointDurableExecution(request);
        
        return response.checkpointToken();
    }
    
    private Operation convertOperation(software.amazon.awssdk.services.lambda.model.Operation awsOp) {
        String result = null;
        String status = awsOp.statusAsString();
        
        // Extract result based on operation type
        if (awsOp.stepDetails() != null) {
            result = awsOp.stepDetails().result();
        } else if (awsOp.callbackDetails() != null) {
            result = awsOp.callbackDetails().result();
        } else if (awsOp.contextDetails() != null) {
            result = awsOp.contextDetails().result();
        }
        
        return new Operation(
            awsOp.id(),
            awsOp.name(),
            result,
            status
        );
    }
    
    private software.amazon.awssdk.services.lambda.model.OperationUpdate convertOperationUpdate(OperationUpdate update) {
        return software.amazon.awssdk.services.lambda.model.OperationUpdate.builder()
            .id(update.getId())
            .type(update.getType())
            .action(update.getAction())
            .name(update.getName())
            .payload(update.getPayload())
            .build();
    }
}
```

### Step 23.3: Add API error handling and retries
```java
public class LambdaDurableExecutionClient implements DurableExecutionClient {
    
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 100;
    
    @Override
    public String checkpoint(String durableExecutionArn, String checkpointToken, List<OperationUpdate> updates) {
        int attempt = 0;
        Exception lastException = null;
        
        while (attempt < MAX_RETRIES) {
            try {
                var awsUpdates = new ArrayList<software.amazon.awssdk.services.lambda.model.OperationUpdate>();
                for (var update : updates) {
                    awsUpdates.add(convertOperationUpdate(update));
                }
                
                var request = CheckpointDurableExecutionRequest.builder()
                    .durableExecutionArn(durableExecutionArn)
                    .checkpointToken(checkpointToken)
                    .operations(awsUpdates)
                    .build();
                
                var response = lambdaClient.checkpointDurableExecution(request);
                return response.checkpointToken();
                
            } catch (InvalidParameterValueException e) {
                // Invalid checkpoint token - let it propagate (may resolve on retry)
                if (e.getMessage().contains("Invalid checkpoint token")) {
                    throw e;
                }
                // Other validation errors are terminal
                throw new CheckpointException("Invalid parameter: " + e.getMessage(), e);
                
            } catch (ResourceNotFoundException e) {
                // Execution not found - terminal error
                throw new CheckpointException("Execution not found: " + durableExecutionArn, e);
                
            } catch (ThrottlingException e) {
                // Rate limit - retry with backoff
                lastException = e;
                attempt++;
                if (attempt < MAX_RETRIES) {
                    try {
                        long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new CheckpointException("Interrupted during retry backoff", ie);
                    }
                }
                
            } catch (LambdaException e) {
                // Other Lambda service errors - retry
                lastException = e;
                attempt++;
                if (attempt < MAX_RETRIES) {
                    try {
                        long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new CheckpointException("Interrupted during retry backoff", ie);
                    }
                }
            }
        }
        
        throw new CheckpointException("Failed after " + MAX_RETRIES + " retries", lastException);
    }
}
```

### Step 23.4: Update DurableHandler to use Lambda client in production
```java
public abstract class DurableHandler<I, O> implements RequestStreamHandler {
    
    @Override
    protected DurableExecutionClient createClient() {
        // Check if running in Lambda (has AWS_LAMBDA_FUNCTION_NAME env var)
        if (System.getenv("AWS_LAMBDA_FUNCTION_NAME") != null) {
            return new LambdaDurableExecutionClient();
        } else {
            // Local testing
            return new InMemoryDurableExecutionClient();
        }
    }
}
```

### ✅ Verify
```bash
mvn clean compile
# Should compile successfully with AWS SDK and error handling
```

**What you added:**
- API error handling (InvalidParameterValueException, ResourceNotFoundException, ThrottlingException)
- Exponential backoff retry logic
- Proper error propagation

**Note:** Full testing requires Lambda deployment. For now, verify compilation.

---

## 🎯 Increment 24: Checkpoint Batching (45 min)

**Goal:** Batch multiple checkpoint calls to reduce API overhead.

### Step 24.1: Create CheckpointBatcher
```java
package com.amazonaws.lambda.durable.internal;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import com.amazonaws.lambda.durable.model.OperationUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Batches checkpoint operations to reduce API calls.
 * Flushes every 100ms or when batch reaches 100 operations.
 */
public class CheckpointBatcher {
    
    private final DurableExecutionClient client;
    private final String durableExecutionArn;
    private final BlockingQueue<OperationUpdate> pendingUpdates = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile String currentToken;
    
    public CheckpointBatcher(DurableExecutionClient client, String durableExecutionArn, String initialToken) {
        this.client = client;
        this.durableExecutionArn = durableExecutionArn;
        this.currentToken = initialToken;
        
        // Start background flusher
        scheduler.scheduleAtFixedRate(this::flush, 100, 100, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Queue an update for batching.
     */
    public void queueUpdate(OperationUpdate update) {
        pendingUpdates.offer(update);
    }
    
    /**
     * Flush pending updates immediately.
     */
    public synchronized void flush() {
        var batch = new ArrayList<OperationUpdate>();
        pendingUpdates.drainTo(batch, 100);  // Max 100 per batch
        
        if (!batch.isEmpty()) {
            System.out.println("Flushing " + batch.size() + " checkpoint updates");
            currentToken = client.checkpoint(durableExecutionArn, currentToken, batch);
        }
    }
    
    public String getCurrentToken() {
        return currentToken;
    }
    
    public void shutdown() {
        flush();  // Final flush
        scheduler.shutdown();
    }
}
```

### Step 24.2: Update DurableContext to use batcher
```java
public class DurableContext {
    
    private final CheckpointBatcher batcher;
    
    public DurableContext(String durableExecutionArn, DurableExecutionClient client, SerDes serDes) {
        this.durableExecutionArn = durableExecutionArn;
        this.client = client;
        this.serDes = serDes;
        this.executor = createDefaultExecutor();
        
        // Initialize batcher
        var initialToken = (client instanceof InMemoryDurableExecutionClient) 
            ? ((InMemoryDurableExecutionClient) client).getCurrentToken()
            : "initial-token";
        this.batcher = new CheckpointBatcher(client, durableExecutionArn, initialToken);
        
        // Load initial state
        var operations = client.getExecutionState(durableExecutionArn, initialToken);
        this.operationCache = new HashMap<>();
        for (var op : operations) {
            operationCache.put(op.getId(), op);
        }
    }
    
    public <T> T step(String name, Class<T> type, Callable<T> action) {
        var opId = String.valueOf(operationCounter++);
        
        // Check replay
        var existing = operationCache.get(opId);
        if (existing != null && existing.isSucceeded()) {
            System.out.println("REPLAY: Skipping step: " + name + " (id=" + opId + ")");
            return serDes.deserialize(existing.getResult(), type);
        }
        
        try {
            System.out.println("Executing step: " + name + " (id=" + opId + ")");
            
            // Queue START checkpoint (batched)
            batcher.queueUpdate(new OperationUpdate(opId, "STEP", "START", name, null));
            
            // Execute action
            T result = action.call();
            
            // Queue SUCCEED checkpoint (batched)
            var resultJson = serDes.serialize(result);
            batcher.queueUpdate(new OperationUpdate(opId, "STEP", "SUCCEED", name, resultJson));
            
            // For critical operations, flush immediately
            batcher.flush();
            
            // Update cache
            operationCache.put(opId, new Operation(opId, name, resultJson, "SUCCEEDED"));
            
            System.out.println("Step completed: " + name);
            return result;
            
        } catch (Exception e) {
            // Queue FAIL checkpoint
            var errorJson = serDes.serialize(Map.of(
                "errorType", e.getClass().getSimpleName(),
                "errorMessage", e.getMessage()
            ));
            batcher.queueUpdate(new OperationUpdate(opId, "STEP", "FAIL", name, errorJson));
            batcher.flush();  // Flush immediately on error
            
            throw new RuntimeException("Step failed: " + name, e);
        }
    }
    
    public void shutdown() {
        batcher.shutdown();
        executor.shutdown();
    }
}
```

### ✅ Verify
```bash
mvn test
# Tests should pass with batching
# Console should show "Flushing N checkpoint updates"
```

---

## 🎯 Increment 25: Update Wait Operation (30 min)

**Goal:** Update wait() to use proper checkpoint operations.

### Step 25.1: Update wait() implementation
```java
public void wait(Duration duration) {
    var opId = String.valueOf(operationCounter++);
    
    // Check replay
    var existing = operationCache.get(opId);
    if (existing != null && existing.isSucceeded()) {
        System.out.println("REPLAY: wait " + duration + " (id=" + opId + ")");
        return;  // Already waited
    }
    
    System.out.println("Waiting: " + duration + " (id=" + opId + ")");
    
    // Checkpoint WAIT START
    batcher.queueUpdate(new OperationUpdate(
        opId,
        "WAIT",
        "START",
        "wait-" + duration,
        String.valueOf(System.currentTimeMillis() + duration.toMillis())
    ));
    batcher.flush();
    
    // Update cache
    operationCache.put(opId, new Operation(opId, "wait", null, "STARTED"));
    
    // Return PENDING status - Lambda will reinvoke when wait completes
    System.out.println("Wait started - execution will suspend");
}
```

### Step 25.2: Update DurableHandler to handle PENDING status
```java
@Override
public void handleRequest(InputStream input, OutputStream output, Context lambdaContext) {
    try {
        var envelope = envelopeParser.parse(input);
        var client = createClient();
        var durableContext = new DurableContext(envelope.getDurableExecutionArn(), client, serDes);
        durableContext.loadInitialState(envelope.getCheckpointToken(), envelope.getInitialExecutionState().getOperations());
        
        // Call user handler
        O result = handleRequest(durableContext);
        
        // Check if there are pending operations
        if (durableContext.hasPendingOperations()) {
            writeResponse(output, "PENDING", null, null);
        } else {
            writeResponse(output, "SUCCEEDED", serDes.serialize(result), null);
        }
        
        // Cleanup
        durableContext.shutdown();
        
    } catch (Exception e) {
        var error = Map.of(
            "ErrorType", e.getClass().getSimpleName(),
            "ErrorMessage", e.getMessage()
        );
        writeResponse(output, "FAILED", null, error);
    }
}
```

### Step 25.3: Add hasPendingOperations() to DurableContext
```java
public boolean hasPendingOperations() {
    return operationCache.values().stream()
        .anyMatch(op -> "STARTED".equals(op.getStatus()) || "PENDING".equals(op.getStatus()));
}
```

### ✅ Verify
```bash
mvn test
# Wait operation should checkpoint properly
```

---

## 🎯 Increment 26: Exception Classes (30 min)

**Goal:** Proper exception hierarchy with error classification and size limit handling.

### Step 26.1: Create exception classes
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

```java
package com.amazonaws.lambda.durable.exception;

public class StepFailedException extends DurableExecutionException {
    private final String stepName;
    
    public StepFailedException(String stepName, Throwable cause) {
        super("Step failed: " + stepName, cause);
        this.stepName = stepName;
    }
    
    public String getStepName() {
        return stepName;
    }
}
```

```java
package com.amazonaws.lambda.durable.exception;

public class CheckpointException extends DurableExecutionException {
    public CheckpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
package com.amazonaws.lambda.durable.exception;

/**
 * Thrown when payload size exceeds Lambda limits.
 * Per SDK specification, this should NOT trigger invocation-level retries.
 */
public class PayloadSizeException extends DurableExecutionException {
    private final int payloadSize;
    private final int maxSize;
    
    public PayloadSizeException(int payloadSize, int maxSize) {
        super(String.format("Payload size (%d bytes) exceeds maximum (%d bytes)", payloadSize, maxSize));
        this.payloadSize = payloadSize;
        this.maxSize = maxSize;
    }
    
    public int getPayloadSize() { return payloadSize; }
    public int getMaxSize() { return maxSize; }
}
```

### Step 26.2: Add error classification utility
```java
package com.amazonaws.lambda.durable.internal;

/**
 * Classifies errors per SDK specification Section 8.1.
 * Runtime errors (Runtime.*, Sandbox.*, Extension.*) are retried by Lambda.
 * Handler errors are retried with exponential backoff.
 */
public class ErrorClassifier {
    
    /**
     * Check if error is a runtime error that Lambda will retry immediately.
     */
    public static boolean isRuntimeError(Throwable error) {
        if (error == null) return false;
        
        var errorType = error.getClass().getSimpleName();
        
        // Runtime errors per specification
        if (errorType.startsWith("Runtime.") || 
            errorType.startsWith("Sandbox.") || 
            errorType.startsWith("Extension.")) {
            
            // Exception: Sandbox.Timedout is treated as handler error
            if ("Sandbox.Timedout".equals(errorType)) {
                return false;
            }
            
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if error should be retried (handler error).
     */
    public static boolean isRetryable(Throwable error) {
        // Runtime errors are handled by Lambda, not SDK
        if (isRuntimeError(error)) {
            return false;
        }
        
        // Size limit errors are terminal
        if (error instanceof PayloadSizeException) {
            return false;
        }
        
        // All other handler errors are retryable
        return true;
    }
}
```

### Step 26.3: Add size limit checking
```java
package com.amazonaws.lambda.durable.internal;

import com.amazonaws.lambda.durable.exception.PayloadSizeException;

/**
 * Validates payload sizes per Lambda limits.
 */
public class PayloadValidator {
    
    // Lambda limits (subject to change - check AWS docs)
    private static final int MAX_RESPONSE_SIZE = 6 * 1024 * 1024;  // 6MB
    private static final int MAX_CHECKPOINT_SIZE = 256 * 1024;      // 256KB per operation
    
    /**
     * Validate response payload size.
     */
    public static void validateResponseSize(String payload) {
        if (payload == null) return;
        
        int size = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (size > MAX_RESPONSE_SIZE) {
            throw new PayloadSizeException(size, MAX_RESPONSE_SIZE);
        }
    }
    
    /**
     * Validate checkpoint payload size.
     */
    public static void validateCheckpointSize(String payload) {
        if (payload == null) return;
        
        int size = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (size > MAX_CHECKPOINT_SIZE) {
            throw new PayloadSizeException(size, MAX_CHECKPOINT_SIZE);
        }
    }
}
```

### Step 26.4: Update DurableContext to use error classification
```java
public <T> T step(String name, Class<T> type, Callable<T> action) {
    try {
        // ... existing code ...
        
        // Validate result size before checkpointing
        var resultJson = serDes.serialize(result);
        PayloadValidator.validateCheckpointSize(resultJson);
        
        // ... checkpoint and return ...
        
    } catch (PayloadSizeException e) {
        // Size limit error - terminal, return FAILED
        logger.error("Payload size limit exceeded for step: {}", name, e);
        throw e;
        
    } catch (Exception e) {
        // Classify error
        if (ErrorClassifier.isRuntimeError(e)) {
            // Runtime error - let Lambda handle it
            logger.warn("Runtime error in step {}: {}", name, e.getMessage());
            throw e;
        } else if (ErrorClassifier.isRetryable(e)) {
            // Handler error - checkpoint FAIL and let Lambda retry
            logger.error("Step failed (retryable): {}", name, e);
            throw new StepFailedException(name, e);
        } else {
            // Terminal error
            logger.error("Step failed (terminal): {}", name, e);
            throw new StepFailedException(name, e);
        }
    }
}
```

### Step 26.5: Update DurableHandler to handle size limits
```java
@Override
public void handleRequest(InputStream input, OutputStream output, Context lambdaContext) {
    try {
        // ... existing code ...
        
        // Validate response size
        var resultJson = serDes.serialize(result);
        PayloadValidator.validateResponseSize(resultJson);
        
        writeResponse(output, "SUCCEEDED", resultJson, null);
        
    } catch (PayloadSizeException e) {
        // Size limit - return FAILED (don't retry)
        var error = Map.of(
            "ErrorType", "PayloadSizeException",
            "ErrorMessage", e.getMessage()
        );
        writeResponse(output, "FAILED", null, error);
        
    } catch (Exception e) {
        // Other errors
        var error = Map.of(
            "ErrorType", e.getClass().getSimpleName(),
            "ErrorMessage", e.getMessage()
        );
        writeResponse(output, "FAILED", null, error);
    }
}
```

### ✅ Verify
```bash
mvn test
# Tests should use new exceptions and error classification
```

**What you added:**
- Error classification (Runtime.* vs handler errors)
- Size limit validation (6MB response, 256KB checkpoint)
- PayloadSizeException for terminal size errors
- Proper error handling per SDK specification

---

## 🎯 Increment 27: Logging (45 min)

**Goal:** Replace System.out with proper logging and add execution context.

### Step 27.1: Add SLF4J dependency
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

### Step 27.2: Create execution context for logging
```java
package com.amazonaws.lambda.durable.internal;

/**
 * Execution context for structured logging.
 * Per SDK specification Section 10.2.
 */
public class ExecutionContext {
    private final String durableExecutionArn;
    private final String requestId;
    private String currentOperationId;
    
    public ExecutionContext(String durableExecutionArn, String requestId) {
        this.durableExecutionArn = durableExecutionArn;
        this.requestId = requestId;
    }
    
    public String getDurableExecutionArn() { return durableExecutionArn; }
    public String getRequestId() { return requestId; }
    public String getCurrentOperationId() { return currentOperationId; }
    
    public void setCurrentOperationId(String operationId) {
        this.currentOperationId = operationId;
    }
    
    /**
     * Format context for logging.
     */
    public String toLogString() {
        return String.format("[arn=%s, requestId=%s, opId=%s]", 
            durableExecutionArn, 
            requestId, 
            currentOperationId != null ? currentOperationId : "none");
    }
}
```

### Step 27.3: Update DurableContext with logger and context
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class DurableContext {
    private static final Logger logger = LoggerFactory.getLogger(DurableContext.class);
    
    private final ExecutionContext executionContext;
    
    public DurableContext(String durableExecutionArn, DurableExecutionClient client, SerDes serDes, String requestId) {
        this.durableExecutionArn = durableExecutionArn;
        this.client = client;
        this.serDes = serDes;
        this.executor = createDefaultExecutor();
        this.executionContext = new ExecutionContext(durableExecutionArn, requestId);
        
        // Set MDC for structured logging
        MDC.put("durableExecutionArn", durableExecutionArn);
        MDC.put("requestId", requestId);
        
        // ... rest of initialization ...
    }
    
    public <T> T step(String name, Class<T> type, Callable<T> action) {
        var opId = String.valueOf(operationCounter++);
        
        // Set operation context
        executionContext.setCurrentOperationId(opId);
        MDC.put("operationId", opId);
        
        var existing = operationCache.get(opId);
        if (existing != null && existing.isSucceeded()) {
            logger.info("REPLAY: Skipping step: {} {}", name, executionContext.toLogString());
            return serDes.deserialize(existing.getResult(), type);
        }
        
        try {
            logger.info("Executing step: {} {}", name, executionContext.toLogString());
            
            // ... existing code ...
            
            logger.info("Step completed: {} {}", name, executionContext.toLogString());
            return result;
            
        } catch (Exception e) {
            logger.error("Step failed: {} {} - {}", name, executionContext.toLogString(), e.getMessage(), e);
            throw new StepFailedException(name, e);
        } finally {
            MDC.remove("operationId");
        }
    }
    
    public void shutdown() {
        batcher.shutdown();
        executor.shutdown();
        
        // Clean up MDC
        MDC.remove("durableExecutionArn");
        MDC.remove("requestId");
        MDC.remove("operationId");
    }
}
```

### Step 27.4: Update DurableHandler to pass request ID
```java
@Override
public void handleRequest(InputStream input, OutputStream output, Context lambdaContext) {
    try {
        var envelope = envelopeParser.parse(input);
        var client = createClient();
        
        // Pass Lambda request ID for logging
        var durableContext = new DurableContext(
            envelope.getDurableExecutionArn(), 
            client, 
            serDes,
            lambdaContext.getAwsRequestId()
        );
        
        // ... rest of handler ...
    }
}
```

### Step 27.5: Update CheckpointBatcher with logging
```java
public class CheckpointBatcher {
    private static final Logger logger = LoggerFactory.getLogger(CheckpointBatcher.class);
    
    public synchronized void flush() {
        var batch = new ArrayList<OperationUpdate>();
        pendingUpdates.drainTo(batch, 100);
        
        if (!batch.isEmpty()) {
            logger.debug("Flushing {} checkpoint updates", batch.size());
            currentToken = client.checkpoint(durableExecutionArn, currentToken, batch);
            logger.debug("Checkpoint successful, new token: {}", currentToken);
        }
    }
}
```

### ✅ Verify
```bash
mvn test
# Should see proper log output with execution context
# Log format: [arn=..., requestId=..., opId=...] message
```

**What you added:**
- ExecutionContext class for structured logging
- SLF4J MDC (Mapped Diagnostic Context) for thread-local context
- DurableExecutionArn, RequestId, OperationId in all log messages
- Proper log levels (info, debug, error)
- Context cleanup on shutdown

---
</dependency>
```

### Step 27.2: Replace System.out with logger
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DurableContext {
    private static final Logger logger = LoggerFactory.getLogger(DurableContext.class);
    
    public <T> T step(String name, Class<T> type, Callable<T> action) {
        var opId = String.valueOf(operationCounter++);
        
        var existing = operationCache.get(opId);
        if (existing != null && existing.isSucceeded()) {
            logger.info("REPLAY: Skipping step: {} (id={})", name, opId);
            return serDes.deserialize(existing.getResult(), type);
        }
        
        try {
            logger.info("Executing step: {} (id={})", name, opId);
            // ... rest of implementation ...
            logger.info("Step completed: {}", name);
            return result;
        } catch (Exception e) {
            logger.error("Step failed: {}", name, e);
            throw new StepFailedException(name, e);
        }
    }
}
```

### ✅ Verify
```bash
mvn test
# Should see proper log output
```

---

## 🎯 Increment 28: Documentation (45 min)

**Goal:** Complete README and JavaDoc.

### Step 28.1: Update README.md
```markdown
# AWS Lambda Durable Execution Java SDK

Simple, idiomatic Java SDK for AWS Lambda Durable Executions.

## Features

- ✅ Step execution with automatic checkpointing
- ✅ Replay from checkpoint log
- ✅ Wait operations (suspend without charges)
- ✅ Async execution with DurableFuture
- ✅ Checkpoint batching (100ms window)
- ✅ Local testing with in-memory client
- ✅ Production deployment with Lambda client

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
    protected OrderResult handleRequest(DurableContext ctx) throws Exception {
        // Step 1: Process order
        Order order = ctx.step("process", Order.class, () -> 
            processOrder(event)
        );
        
        // Step 2: Wait for payment
        ctx.wait(Duration.ofMinutes(30));
        
        // Step 3: Complete order
        String confirmation = ctx.step("complete", String.class, () ->
            completeOrder(order)
        );
        
        return new OrderResult(confirmation);
    }
}
```

### 3. Deploy to Lambda

Configure Lambda function with durable execution enabled.

## API Reference

See JavaDoc for complete API documentation.

## Examples

See `examples/` directory for more examples.

## License

Apache 2.0
```

### Step 28.2: Add JavaDoc to public APIs
Add comprehensive JavaDoc to:
- DurableContext
- DurableHandler
- DurableFuture
- DurableExecutionClient

### ✅ Verify
```bash
mvn javadoc:javadoc
# Should generate docs without errors
```

---

## 🎯 Increment 29: Integration Test (30 min)

**Goal:** End-to-end test with realistic workflow.

### Step 29.1: Create integration test
```java
package com.amazonaws.lambda.durable;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationTest {
    
    @Test
    void testCompleteWorkflow() {
        var handler = new TestHandler();
        
        // Simulate Lambda invocation
        var envelope = createTestEnvelope();
        var result = handler.handleDurableRequest(envelope);
        
        assertEquals("SUCCEEDED", result.getStatus());
        assertNotNull(result.getResult());
    }
    
    @Test
    void testReplayAfterWait() {
        var handler = new TestHandler();
        
        // First invocation - executes until wait
        var envelope1 = createTestEnvelope();
        var result1 = handler.handleDurableRequest(envelope1);
        assertEquals("PENDING", result1.getStatus());
        
        // Second invocation - replays and continues
        var envelope2 = createTestEnvelopeWithCompletedWait();
        var result2 = handler.handleDurableRequest(envelope2);
        assertEquals("SUCCEEDED", result2.getStatus());
    }
    
    static class TestHandler extends DurableHandler<String, String> {
        @Override
        protected String handleRequest(DurableContext ctx) throws Exception {
            var step1 = ctx.step("step1", String.class, () -> "result1");
            ctx.wait(Duration.ofMinutes(5));
            var step2 = ctx.step("step2", String.class, () -> "result2");
            return step1 + "," + step2;
        }
    }
}
```

### ✅ Verify
```bash
mvn test
# Integration test should pass
```

---

## 🎯 Increment 30: Final Polish (30 min)

**Goal:** Clean up and finalize.

### Step 30.1: Remove debug code
- Remove unnecessary System.out.println
- Clean up commented code
- Fix any TODOs

### Step 30.2: Format code
```bash
# Format all Java files
mvn spotless:apply  # If using Spotless
```

### Step 30.3: Final verification
```bash
mvn clean test
mvn javadoc:javadoc
mvn package
```

### Step 30.4: Update TODO.md
Mark all increments complete!

### ✅ Final Checklist

- [ ] All 30 increments complete
- [ ] All tests pass
- [ ] ~500 LOC core code
- [ ] JavaDoc complete
- [ ] README updated
- [ ] Examples work
- [ ] No compiler warnings
- [ ] Ready for deployment

---

## 🎉 Completion

**Congratulations!** You've built a complete Lambda Durable Execution Java SDK with:

- ✅ Proper Lambda API integration
- ✅ Checkpoint batching
- ✅ Replay logic
- ✅ Local testing support
- ✅ Production deployment ready
- ✅ Comprehensive documentation

**Next Steps:**
1. Deploy to Lambda
2. Test with real workloads
3. Gather feedback
4. Add advanced features (parallel, callbacks, invoke)

---

**Status:** Complete  
**Total Time:** ~15-18 hours  
**Final LOC:** ~500 core code
