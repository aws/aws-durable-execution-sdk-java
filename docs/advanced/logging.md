## Logging

The SDK provides a shared `DurableLogger` via `getLogger()` that automatically includes execution metadata in log entries and suppresses duplicate logs during replay.

### Basic Usage

```java
import static software.amazon.lambda.durable.logging.DurableLogger.getLogger;

@Override
protected OrderResult handleRequest(Order order, DurableContext ctx) {
    getLogger().info("Processing order: {}", order.getId());
    
    var result = ctx.step("validate", String.class, stepCtx -> {
        getLogger().debug("Validating order details");
        return validate(order);
    });
    
    getLogger().info("Order processed successfully");
    return new OrderResult(result);
}
```

Use `getLogger(existingLogger)` to wrap an existing SLF4J logger while retaining durable execution context.

### Log Output

Logs include execution context via MDC (works with any SLF4J-compatible logging framework):

```json
{
  "timestamp": "2024-01-15T10:30:00.000Z",
  "level": "INFO",
  "message": "Processing order: ORD-123",
  "durableExecutionArn": "arn:aws:lambda:us-east-1:123456789:function:order-processor:exec-abc123",
  "requestId": "a1b2c3d4-5678-90ab-cdef-example12345",
  "operationId": "1",
  "operationName": "validate"
}
```

### Replay Behavior

By default, logs are suppressed during replay to avoid duplicates:

```
First Invocation:
  [INFO] Processing order: ORD-123          ✓ Logged
  [DEBUG] Validating order details          ✓ Logged

Replay (after wait):
  [INFO] Processing order: ORD-123          ✗ Suppressed (already logged)
  [DEBUG] Validating order details          ✗ Suppressed
  [INFO] Continuing after wait              ✓ Logged (new code path)
```

To log during replay (e.g., for debugging):

```java
@Override
protected DurableConfig createConfiguration() {
    return DurableConfig.builder()
        .withLoggerConfig(LoggerConfig.withReplayLogging())
        .build();
}
```
