# Error Handling Design

**Date:** December 6, 2025  
**Status:** Active Design - MVP

## Overview

This document details exception handling, retry policies, and error propagation in the SDK.

## 1. Exception Hierarchy

### SDK Exceptions

```
RuntimeException
├── DurableExecutionException (base)
│   ├── StepFailedException
│   ├── StepInterruptedException
│   ├── ExecutionSuspendedException
│   ├── SerDesException
│   └── CheckpointException
```

### Base Exception

```java
package com.amazonaws.lambda.durable.exception;

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
```

### StepFailedException

```java
/**
 * Thrown when a step fails and won't be retried.
 */
public class StepFailedException extends DurableExecutionException {
    private final String stepName;
    private final String operationId;
    
    public StepFailedException(String stepName, String operationId, Throwable cause) {
        super("Step '" + stepName + "' failed: " + cause.getMessage(), cause);
        this.stepName = stepName;
        this.operationId = operationId;
    }
    
    public String getStepName() { return stepName; }
    public String getOperationId() { return operationId; }
}
```

### StepInterruptedException

```java
/**
 * Thrown when a step with AT_MOST_ONCE semantics was interrupted.
 */
public class StepInterruptedException extends DurableExecutionException {
    public StepInterruptedException(String message) {
        super(message);
    }
}
```

### ExecutionSuspendedException

```java
/**
 * Internal exception to signal execution suspension.
 * Not exposed to users.
 */
class ExecutionSuspendedException extends DurableExecutionException {
    public ExecutionSuspendedException() {
        super("Execution suspended");
    }
}
```

### SerDesException

```java
/**
 * Thrown when serialization/deserialization fails.
 */
public class SerDesException extends DurableExecutionException {
    public SerDesException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### CheckpointException

```java
/**
 * Thrown when checkpoint API call fails.
 */
public class CheckpointException extends DurableExecutionException {
    public CheckpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

## 2. Retry Policy

### RetryPolicy Interface

```java
package com.amazonaws.lambda.durable.retry;

/**
 * Policy for deciding whether to retry failed operations.
 */
public interface RetryPolicy {
    
    /**
     * Decide whether to retry after a failure.
     * 
     * @param error the exception that occurred
     * @param attempt the attempt number (0-indexed)
     * @return retry decision
     */
    RetryDecision decide(Throwable error, int attempt);
    
    // Factory methods
    static RetryPolicy exponentialBackoff(int maxAttempts) {
        return new ExponentialBackoffRetryPolicy(maxAttempts);
    }
    
    static RetryPolicy noRetry() {
        return (error, attempt) -> RetryDecision.fail();
    }
}
```

### RetryDecision

```java
package com.amazonaws.lambda.durable.retry;

import java.time.Duration;

/**
 * Decision about whether to retry an operation.
 */
public class RetryDecision {
    private final boolean shouldRetry;
    private final Duration delay;
    
    private RetryDecision(boolean shouldRetry, Duration delay) {
        this.shouldRetry = shouldRetry;
        this.delay = delay;
    }
    
    public boolean shouldRetry() { return shouldRetry; }
    public Duration getDelay() { return delay; }
    
    public static RetryDecision retry(Duration delay) {
        return new RetryDecision(true, delay);
    }
    
    public static RetryDecision fail() {
        return new RetryDecision(false, null);
    }
}
```

## 3. Exponential Backoff (Default)

### Implementation

```java
package com.amazonaws.lambda.durable.retry;

import java.time.Duration;

/**
 * Exponential backoff retry policy.
 * Delays: 1s, 2s, 4s, 8s, 16s, 32s, 60s (capped)
 */
public class ExponentialBackoffRetryPolicy implements RetryPolicy {
    
    private final int maxAttempts;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double multiplier;
    
    public ExponentialBackoffRetryPolicy(int maxAttempts) {
        this(maxAttempts, Duration.ofSeconds(1), Duration.ofSeconds(60), 2.0);
    }
    
    public ExponentialBackoffRetryPolicy(int maxAttempts, Duration initialDelay, 
                                         Duration maxDelay, double multiplier) {
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.multiplier = multiplier;
    }
    
    @Override
    public RetryDecision decide(Throwable error, int attempt) {
        // Check if we've exceeded max attempts
        if (attempt >= maxAttempts) {
            return RetryDecision.fail();
        }
        
        // Calculate delay: initialDelay * (multiplier ^ attempt)
        long delaySeconds = (long) (initialDelay.toSeconds() * Math.pow(multiplier, attempt));
        Duration delay = Duration.ofSeconds(Math.min(delaySeconds, maxDelay.toSeconds()));
        
        return RetryDecision.retry(delay);
    }
}
```

### Usage

```java
// Default: 5 attempts with exponential backoff
String result = ctx.step("api-call", String.class,
    StepConfig.builder()
        .retryPolicy(RetryPolicy.exponentialBackoff(5))
        .build(),
    () -> callExternalApi()
);

// Delays: 1s, 2s, 4s, 8s, 16s
// After 5 failures, step fails permanently
```

## 4. Error Propagation

### In Step Execution

```java
public <T> T step(String name, Class<T> type, Callable<T> action) {
    try {
        // Execute action
        T result = action.call();
        return result;
        
    } catch (Exception e) {
        // Ask retry policy
        RetryDecision decision = retryPolicy.decide(e, attempt);
        
        if (decision.shouldRetry()) {
            // Checkpoint retry, suspend execution
            checkpointRetry(operationId, name, e, decision.getDelay());
            // Execution will be re-invoked after delay
            throw new ExecutionSuspendedException();
            
        } else {
            // No retry, fail permanently
            checkpointFail(operationId, name, e);
            throw new StepFailedException(name, operationId, e);
        }
    }
}
```

### In User Handler

```java
public class MyHandler extends DurableHandler<MyEvent, String> {
    @Override
    public String handleRequest(MyEvent event, DurableContext ctx) {
        try {
            String result = ctx.step("process", String.class, () -> 
                riskyOperation(event)
            );
            return result;
            
        } catch (StepFailedException e) {
            // Step failed after retries
            logger.log("Step failed: " + e.getStepName());
            
            // Can handle or rethrow
            return "fallback-value";
        }
    }
}
```

### Uncaught Exceptions

```java
public String handleRequest(MyEvent event, DurableContext ctx) {
    // If user throws exception, execution fails
    throw new RuntimeException("Unhandled error");
    
    // SDK catches this and:
    // 1. Checkpoints execution failure
    // 2. Returns FAILED status to service
}
```

## 5. Retry Scenarios

### Scenario 1: Transient Error (Retry Succeeds)

```java
String result = ctx.step("api", String.class, () -> callApi());
```

**Timeline:**
1. **Attempt 0:** callApi() throws TimeoutException
2. **Decision:** Retry in 1s
3. **Checkpoint:** RETRY with delay=1s
4. **Suspend:** Execution ends
5. **Re-invoke:** After 1s, service re-invokes
6. **Attempt 1:** callApi() succeeds
7. **Checkpoint:** SUCCEED with result
8. **Return:** result to user

### Scenario 2: Permanent Error (No Retry)

```java
String result = ctx.step("validate", String.class, () -> validate(input));
```

**Timeline:**
1. **Attempt 0:** validate() throws ValidationException
2. **Decision:** Don't retry (validation errors are permanent)
3. **Checkpoint:** FAIL with error
4. **Throw:** StepFailedException to user

### Scenario 3: Max Retries Exceeded

```java
String result = ctx.step("flaky", String.class,
    StepConfig.builder()
        .retryPolicy(RetryPolicy.exponentialBackoff(3))
        .build(),
    () -> flakyOperation()
);
```

**Timeline:**
1. **Attempt 0:** Fails, retry in 1s
2. **Attempt 1:** Fails, retry in 2s
3. **Attempt 2:** Fails, retry in 4s
4. **Attempt 3:** Fails, no more retries
5. **Checkpoint:** FAIL with error
6. **Throw:** StepFailedException to user

## 6. Custom Retry Policies

### Conditional Retry

```java
RetryPolicy customPolicy = (error, attempt) -> {
    // Don't retry validation errors
    if (error instanceof ValidationException) {
        return RetryDecision.fail();
    }
    
    // Retry timeout errors with fixed delay
    if (error instanceof TimeoutException) {
        return RetryDecision.retry(Duration.ofSeconds(5));
    }
    
    // Retry rate limit errors with longer delay
    if (error instanceof RateLimitException) {
        return RetryDecision.retry(Duration.ofMinutes(1));
    }
    
    // Default: exponential backoff
    if (attempt < 5) {
        long delay = (long) Math.pow(2, attempt);
        return RetryDecision.retry(Duration.ofSeconds(delay));
    }
    
    return RetryDecision.fail();
};

String result = ctx.step("smart-retry", String.class,
    StepConfig.builder()
        .retryPolicy(customPolicy)
        .build(),
    () -> operation()
);
```

### Retry with Jitter

```java
public class JitteredBackoffRetryPolicy implements RetryPolicy {
    private final Random random = new Random();
    private final int maxAttempts;
    
    @Override
    public RetryDecision decide(Throwable error, int attempt) {
        if (attempt >= maxAttempts) {
            return RetryDecision.fail();
        }
        
        // Base delay with jitter
        long baseDelay = (long) Math.pow(2, attempt);
        long jitter = random.nextInt(1000); // 0-1000ms
        Duration delay = Duration.ofSeconds(baseDelay).plusMillis(jitter);
        
        return RetryDecision.retry(delay);
    }
}
```

## 7. Error Context

### Preserving Error Information

```java
// During checkpoint
ErrorObject errorObj = ErrorObject.builder()
    .errorType(error.getClass().getName())
    .errorMessage(error.getMessage())
    .stackTrace(serializeStackTrace(error.getStackTrace()))
    .errorData(serDes.serialize(error)) // Full exception
    .build();

checkpointFail(operationId, name, errorObj);
```

### Retrieving Error on Replay

```java
// During replay
Operation failedOp = executionState.getOperation(operationId);
if (failedOp.getStatus() == OperationStatus.FAILED) {
    ErrorObject errorObj = failedOp.getStepDetails().getError();
    
    // Reconstruct exception
    Throwable error = deserializeError(errorObj);
    
    // Throw to user
    throw new StepFailedException(name, operationId, error);
}
```

## 8. Testing

### Test Retry Logic

```java
@Test
void testStepRetry() {
    AtomicInteger attempts = new AtomicInteger(0);
    
    String result = ctx.step("retry-test", String.class,
        StepConfig.builder()
            .retryPolicy(RetryPolicy.exponentialBackoff(3))
            .build(),
        () -> {
            int attempt = attempts.getAndIncrement();
            if (attempt < 2) {
                throw new RuntimeException("Transient error");
            }
            return "success";
        }
    );
    
    assertEquals("success", result);
    assertEquals(3, attempts.get()); // Failed twice, succeeded on third
}
```

### Test Max Retries

```java
@Test
void testMaxRetriesExceeded() {
    assertThrows(StepFailedException.class, () -> {
        ctx.step("always-fails", String.class,
            StepConfig.builder()
                .retryPolicy(RetryPolicy.exponentialBackoff(3))
                .build(),
            () -> {
                throw new RuntimeException("Always fails");
            }
        );
    });
}
```

### Test Custom Retry Policy

```java
@Test
void testCustomRetryPolicy() {
    RetryPolicy policy = (error, attempt) -> {
        if (error instanceof ValidationException) {
            return RetryDecision.fail(); // Don't retry
        }
        return RetryDecision.retry(Duration.ofSeconds(1));
    };
    
    assertThrows(StepFailedException.class, () -> {
        ctx.step("validate", String.class,
            StepConfig.builder()
                .retryPolicy(policy)
                .build(),
            () -> {
                throw new ValidationException("Invalid");
            }
        );
    });
}
```

## 9. Best Practices

### ✅ Do

- Use exponential backoff for transient errors
- Don't retry validation/business logic errors
- Log retry attempts for debugging
- Set reasonable max attempts (3-5)
- Preserve error context in checkpoints

### ❌ Don't

- Don't retry forever (set max attempts)
- Don't use fixed delays (causes thundering herd)
- Don't swallow exceptions silently
- Don't retry non-retryable errors
- Don't use very long delays (> 5 minutes)

## 10. MVP Scope

For MVP, we only need:

✅ **Exception hierarchy** - Base classes  
✅ **ExponentialBackoffRetryPolicy** - Default policy  
✅ **RetryDecision** - Retry or fail  
✅ **Error serialization** - Preserve error info  
✅ **StepFailedException** - User-facing exception  

Not needed for MVP:
❌ Custom retry policies (can add later)  
❌ Jitter (can add later)  
❌ Advanced error handling (circuit breakers, etc.)  

## 11. Open Questions

1. **Max delay:** Should we cap exponential backoff? (Currently 60s)
2. **Jitter:** Add randomness to prevent thundering herd?
3. **Circuit breaker:** Fail fast after repeated failures?
4. **Error budget:** Track error rates and fail fast?

## 12. Next Steps

- [ ] Implement exception classes
- [ ] Implement ExponentialBackoffRetryPolicy
- [ ] Implement RetryDecision
- [ ] Add error serialization utilities
- [ ] Write retry tests
- [ ] Document retry best practices
