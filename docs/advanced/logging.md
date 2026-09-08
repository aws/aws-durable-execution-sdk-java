## Logging

The SDK provides a `DurableLogger` via `ctx.getLogger()` that automatically includes execution metadata in log entries and suppresses duplicate logs during replay.

### Basic Usage

```java
@Override
protected OrderResult handleRequest(Order order, DurableContext ctx) {
    ctx.getLogger().info("Processing order: {}", order.getId());
    
    var result = ctx.step("validate", String.class, stepCtx -> {
        stepCtx.getLogger().debug("Validating order details");
        return validate(order);
    });
    
    ctx.getLogger().info("Order processed successfully");
    return new OrderResult(result);
}
```

### Log Output

Logs include execution context via MDC (works with any SLF4J-compatible logging framework):

```json
{
  "timestamp": "2024-01-15T10:30:00.000Z",
  "level": "INFO",
  "message": "Validating order details",
  "executionArn": "arn:aws:lambda:us-east-1:123456789:function:order-processor:exec-abc123",
  "requestId": "a1b2c3d4-5678-90ab-cdef-example12345",
  "operationId": "1",
  "operationName": "validate",
  "attempt": "1"
}
```

Java SDK 2.x uses `executionArn`, `operationId`, and `operationName` by default.
Applications migrating log queries or dashboards from 1.x can temporarily emit
`durableExecutionArn`, `contextId`, and `contextName` instead:

```java
@Override
protected DurableConfig createConfiguration() {
    return DurableConfig.builder()
        .withLoggerConfig(new LoggerConfig(true, true))
        .build();
}
```

The first argument controls replay suppression. The second enables the old MDC key
names.

### Custom SLF4J Delegate

`getLogger()` uses the SDK's default SLF4J logger. Pass a specific SLF4J `Logger` to
`getLogger(Logger delegate)` when you want the durable metadata and replay behavior
applied to another logger:

```java
private static final Logger applicationLogger =
    LoggerFactory.getLogger(OrderProcessor.class);

var logger = ctx.getLogger(applicationLogger);
logger.info("Processing order: {}", order.getId());
```

This creates a `DurableLogger` around the supplied delegate for that call. It does not
replace the default returned by later parameterless `getLogger()` calls.

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
