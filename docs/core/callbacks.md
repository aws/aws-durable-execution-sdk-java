## createCallback() – Wait for External Events

Callbacks suspend execution until an external system sends a result. Use this for human approvals, webhooks, or any event-driven workflow.

```java
// Create a callback and get the ID to share with external systems
DurableCallbackFuture<String> callback = ctx.createCallback("approval", String.class);

// Send the callback ID to an external system within a step
ctx.step("send-notification", String.class, stepCtx -> {
    notificationService.sendApprovalRequest(callback.callbackId(), requestDetails);
    return "notification-sent";
});

// Suspend until the external system calls back with a result
String approvalResult = callback.get();
```

The external system completes the callback by calling the Lambda Durable Functions API with the callback ID and result payload.

#### waitForCallback() #### 

`waitForCallback` simplifies callback handling by combining callback creation and submission in one operation. The SDK creates the callback, executes your submitter function with the callback ID, and waits for the result.

```java
ctx.waitForCallback("send-notification", String.class, (callbackId, stepCtx) -> {
    notificationService.sendApprovalRequest(callbackId, requestDetails);
})
```

#### Callback Configuration

Configure timeouts and serialization to handle cases where callbacks are never completed or need custom deserialization:

```java
var config = CallbackConfig.builder()
    .timeout(Duration.ofHours(24))        // Max time to wait for callback
    .heartbeatTimeout(Duration.ofHours(1)) // Max time between heartbeats
    .serDes(new CustomSerDes())           // Custom serialization/deserialization
    .build();

var callback = ctx.createCallback("approval", String.class, config);

var waitForCallbackConfig = WaitForCallbackConfig.builder()
    .callbackConfig(config)
    .stepConfig(StepConfig.builder().retryStrategy(...).build())
    .build();
ctx.waitForCallback("approval", String.class, callbackId -> sendApprovalRequest(callbackId), waitForCallbackConfig);
```

| Option | Description |
|--------|-------------|
| `timeout()` | Maximum duration to wait for the callback to complete |
| `heartbeatTimeout()` | Maximum duration between heartbeat signals from the external system |
| `serDes()` | Custom SerDes for deserializing callback results (e.g., encryption, custom formats) |

#### Callback Exceptions

| Exception | When Thrown |
|-----------|-------------|
| `CallbackTimeoutException` | Callback exceeded its timeout duration |
| `CallbackFailedException` | External system sent an error response |

```java
try {
    var result = callback.get();
} catch (CallbackTimeoutException e) {
    // Callback timed out - implement fallback logic
} catch (CallbackFailedException e) {
    // External system reported an error
}
```