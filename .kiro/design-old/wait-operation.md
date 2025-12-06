# Wait Operation Design

**Date:** December 6, 2025  
**Status:** Active Design - MVP

## Overview

This document details how `wait()` operations work - pausing execution for a specified duration.

## 1. Wait API

### Simple API

```java
// Wait for duration
void wait(Duration duration);

// Wait with name (for debugging)
void wait(String name, Duration duration);
```

### Usage Examples

```java
// Wait 5 seconds
ctx.wait(Duration.ofSeconds(5));

// Wait 1 hour
ctx.wait(Duration.ofHours(1));

// Wait with name
ctx.wait("approval-window", Duration.ofHours(24));
```

## 2. Wait Lifecycle

### State Machine

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Check Checkpoint Log                                      │
│    - Does wait operation exist?                              │
└────────────────┬────────────────────────────────────────────┘
                 │
     ┌───────────┴───────────┐
     │                       │
     ▼                       ▼
┌──────────┐          ┌──────────┐
│ Exists   │          │ New      │
└────┬─────┘          └────┬─────┘
     │                     │
     ▼                     ▼
┌──────────────────┐  ┌──────────────────┐
│ Check Status     │  │ Checkpoint START │
└────┬─────────────┘  └────┬─────────────┘
     │                     │
     │                     ▼
     │              ┌──────────────────┐
     │              │ Calculate End    │
     │              │ Timestamp        │
     │              └────┬─────────────┘
     │                   │
     │                   ▼
     │              ┌──────────────────┐
     │              │ Suspend          │
     │              │ Execution        │
     │              └────┬─────────────┘
     │                   │
     └───────────────────┘
                         │
                         ▼
                  ┌──────────────┐
                  │ Return       │
                  └──────────────┘
```

## 3. Implementation

### wait() Method

```java
public void wait(Duration duration) {
    wait(null, duration);
}

public void wait(String name, Duration duration) {
    // 1. Generate operation ID
    String operationId = generateOperationId();
    
    // 2. Check checkpoint log
    Operation existingOp = executionState.getOperation(operationId);
    
    if (existingOp != null) {
        // Replay path - wait already completed
        return handleWaitReplay(operationId, existingOp);
    } else {
        // First execution path
        return executeWait(operationId, name, duration);
    }
}
```

### Execute Wait (First Time)

```java
private void executeWait(String operationId, String name, Duration duration) {
    // Calculate end timestamp
    Instant endTime = Instant.now().plus(duration);
    
    // Checkpoint wait start
    OperationUpdate update = OperationUpdate.builder()
        .id(operationId)
        .name(name)
        .type(OperationType.WAIT)
        .action(OperationAction.START)
        .waitOptions(WaitOptions.builder()
            .waitSeconds((int) duration.toSeconds())
            .build())
        .build();
    
    executionState.checkpoint(update).join();
    
    // Schedule poll for completion
    scheduleWaitPoll(operationId, endTime);
    
    // Suspend execution (no active work)
    // Execution will be re-invoked when wait completes
}
```

### Handle Wait Replay

```java
private void handleWaitReplay(String operationId, Operation existingOp) {
    if (existingOp.getStatus() == OperationStatus.SUCCEEDED) {
        // Wait completed, just return
        return;
    }
    
    // Wait still in progress (shouldn't happen - service completes waits)
    // But handle gracefully
    Instant endTime = existingOp.getWaitDetails().getScheduledEndTimestamp();
    if (Instant.now().isBefore(endTime)) {
        // Still waiting, schedule poll
        scheduleWaitPoll(operationId, endTime);
    }
    // else: wait should complete soon, just return
}
```

### Schedule Poll

```java
private void scheduleWaitPoll(String operationId, Instant endTime) {
    // Calculate when to start polling
    Duration untilEnd = Duration.between(Instant.now(), endTime);
    
    // Poll slightly after expected end time
    Instant firstPoll = endTime.plus(Duration.ofMillis(100));
    
    // Schedule background polling
    executor.schedule(() -> {
        pollForWaitCompletion(operationId);
    }, untilEnd.toMillis() + 100, TimeUnit.MILLISECONDS);
}

private void pollForWaitCompletion(String operationId) {
    // Check if wait completed
    Operation op = executionState.getOperation(operationId);
    
    if (op.getStatus() == OperationStatus.SUCCEEDED) {
        // Wait completed, nothing to do
        return;
    }
    
    // Not complete yet, poll again
    executor.schedule(() -> {
        pollForWaitCompletion(operationId);
    }, 1000, TimeUnit.MILLISECONDS);
}
```

## 4. Service-Side Completion

**Key:** The Durable Execution service completes waits, not the SDK.

### How It Works

1. **SDK checkpoints:** WAIT with duration
2. **SDK suspends:** Execution ends
3. **Service waits:** For specified duration
4. **Service updates:** Operation status to SUCCEEDED
5. **Service re-invokes:** Lambda function
6. **SDK replays:** Wait returns immediately (already SUCCEEDED)

### Why Service-Side?

- ✅ **Accurate timing** - Service has precise timers
- ✅ **No polling overhead** - SDK doesn't need to poll
- ✅ **Efficient** - Lambda not running during wait
- ✅ **Reliable** - Service guarantees completion

## 5. Wait Scenarios

### Scenario 1: Short Wait (< 1 minute)

```java
ctx.wait(Duration.ofSeconds(30));
```

**Timeline:**
1. **Checkpoint:** WAIT with duration=30s
2. **Suspend:** Execution ends
3. **Service waits:** 30 seconds
4. **Service updates:** Operation to SUCCEEDED
5. **Service re-invokes:** Lambda
6. **Replay:** Wait returns immediately

### Scenario 2: Long Wait (hours/days)

```java
ctx.wait(Duration.ofHours(24));
```

**Timeline:**
1. **Checkpoint:** WAIT with duration=86400s
2. **Suspend:** Execution ends
3. **Service waits:** 24 hours
4. **Service updates:** Operation to SUCCEEDED
5. **Service re-invokes:** Lambda
6. **Replay:** Wait returns immediately

### Scenario 3: Multiple Waits

```java
ctx.wait("first", Duration.ofSeconds(5));
ctx.wait("second", Duration.ofSeconds(10));
```

**Timeline:**
1. **First wait:** Checkpoint, suspend, wait 5s, re-invoke
2. **Replay first:** Returns immediately
3. **Second wait:** Checkpoint, suspend, wait 10s, re-invoke
4. **Replay both:** Both return immediately

## 6. Wait with Steps

### Common Pattern

```java
// Step 1: Process
String result = ctx.step("process", String.class, () -> 
    processData(input)
);

// Wait for approval window
ctx.wait("approval-window", Duration.ofHours(24));

// Step 2: Finalize
String final = ctx.step("finalize", String.class, () ->
    finalizeResult(result)
);
```

**Timeline:**
1. **Invocation 1:** Execute step 1, checkpoint success
2. **Invocation 1:** Hit wait, checkpoint, suspend
3. **24 hours later:** Service re-invokes
4. **Invocation 2:** Replay step 1 (cached), replay wait (done)
5. **Invocation 2:** Execute step 2, checkpoint success
6. **Invocation 2:** Return final result

## 7. Checkpoint Format

### Wait Start

```json
{
  "id": "1",
  "name": "approval-window",
  "type": "WAIT",
  "action": "START",
  "waitOptions": {
    "waitSeconds": 86400
  }
}
```

### Service Response (After Wait)

```json
{
  "id": "1",
  "name": "approval-window",
  "type": "WAIT",
  "status": "SUCCEEDED",
  "waitDetails": {
    "scheduledEndTimestamp": "2025-12-07T04:52:43Z",
    "actualEndTimestamp": "2025-12-07T04:52:43Z"
  }
}
```

## 8. Edge Cases

### Wait Duration = 0

```java
ctx.wait(Duration.ZERO);
```

**Behavior:** Returns immediately, no checkpoint needed.

```java
public void wait(Duration duration) {
    if (duration.isZero() || duration.isNegative()) {
        return; // No-op
    }
    // ... normal wait logic
}
```

### Negative Duration

```java
ctx.wait(Duration.ofSeconds(-5));
```

**Behavior:** Treat as zero, return immediately.

### Very Long Wait (> 1 year)

```java
ctx.wait(Duration.ofDays(400));
```

**Behavior:** Service may have max wait duration limit. Document the limit.

## 9. Differences from Step

### Wait vs Step

| Aspect | Wait | Step |
|--------|------|------|
| **Execution** | No user code | Executes Callable |
| **Retry** | No retry | Retry on failure |
| **Completion** | Service-side | SDK-side |
| **Polling** | Minimal | For retry status |
| **Complexity** | Simple | Complex |

### Why Wait is Simpler

- ✅ No user code execution
- ✅ No error handling
- ✅ No retry logic
- ✅ Service handles timing
- ✅ Always succeeds

## 10. Testing

### Test Wait Execution

```java
@Test
void testWait() {
    ExecutionState mockState = mock(ExecutionState.class);
    when(mockState.getOperation("0")).thenReturn(null);
    
    DurableContext ctx = new DurableContext(lambdaContext, mockState, executor);
    
    // Execute wait
    ctx.wait(Duration.ofSeconds(5));
    
    // Verify checkpoint
    ArgumentCaptor<OperationUpdate> captor = ArgumentCaptor.forClass(OperationUpdate.class);
    verify(mockState).checkpoint(captor.capture());
    
    OperationUpdate update = captor.getValue();
    assertEquals(OperationType.WAIT, update.type());
    assertEquals(5, update.waitOptions().waitSeconds());
}
```

### Test Wait Replay

```java
@Test
void testWaitReplay() {
    ExecutionState mockState = mock(ExecutionState.class);
    
    // Setup: Wait already completed
    Operation existingOp = Operation.builder()
        .id("0")
        .type(OperationType.WAIT)
        .status(OperationStatus.SUCCEEDED)
        .build();
    
    when(mockState.getOperation("0")).thenReturn(existingOp);
    
    DurableContext ctx = new DurableContext(lambdaContext, mockState, executor);
    
    // Execute wait (should return immediately)
    ctx.wait(Duration.ofSeconds(5));
    
    // Verify no checkpoint
    verify(mockState, never()).checkpoint(any());
}
```

## 11. Performance

### Wait Cost

- **Checkpoint:** 1 operation (START)
- **Service update:** 1 operation (SUCCEED)
- **Total:** 2 operations per wait

### Lambda Cost

- **Execution time:** Minimal (just checkpoint)
- **Wait time:** No Lambda cost (execution suspended)
- **Re-invocation:** Normal Lambda invocation cost

### Example

```java
ctx.wait(Duration.ofHours(24));
```

**Cost:**
- Lambda execution: ~100ms (checkpoint)
- Wait time: 24 hours (no Lambda cost)
- Re-invocation: ~100ms (replay)
- Total Lambda time: ~200ms

## 12. Best Practices

### ✅ Do

- Use wait for time-based delays
- Use descriptive names for debugging
- Wait for external events (approval, processing)
- Combine with steps for workflows

### ❌ Don't

- Don't use wait for polling (use callbacks instead)
- Don't use very short waits (< 1 second)
- Don't use wait for rate limiting (use step retry)
- Don't wait indefinitely (service may have limits)

## 13. Common Patterns

### Pattern 1: Approval Workflow

```java
// Submit for approval
String requestId = ctx.step("submit", String.class, () -> 
    submitApprovalRequest(data)
);

// Wait for approval window
ctx.wait("approval-window", Duration.ofHours(24));

// Check approval status
String status = ctx.step("check", String.class, () ->
    checkApprovalStatus(requestId)
);
```

### Pattern 2: Rate Limiting

```java
for (int i = 0; i < items.size(); i++) {
    // Process item
    ctx.step("process-" + i, String.class, () -> 
        processItem(items.get(i))
    );
    
    // Wait between items
    if (i < items.size() - 1) {
        ctx.wait(Duration.ofSeconds(1));
    }
}
```

### Pattern 3: Retry with Backoff

```java
// Better: Use step retry instead
ctx.step("api-call", String.class,
    StepConfig.builder()
        .retryPolicy(RetryPolicy.exponentialBackoff(5))
        .build(),
    () -> callApi()
);

// Don't do this:
for (int i = 0; i < 5; i++) {
    try {
        return ctx.step("api-call", String.class, () -> callApi());
    } catch (Exception e) {
        ctx.wait(Duration.ofSeconds((long) Math.pow(2, i)));
    }
}
```

## 14. MVP Scope

For MVP, we only need:

✅ **wait(Duration)** - Basic wait  
✅ **wait(String, Duration)** - Named wait  
✅ **Checkpoint START** - Record wait  
✅ **Handle replay** - Return immediately if done  
✅ **Suspension** - End execution during wait  

Not needed for MVP:
❌ Wait cancellation  
❌ Wait with callback  
❌ Dynamic wait duration  

## 15. Open Questions

1. **Max wait duration:** What's the service limit? (1 year?)
2. **Min wait duration:** Should we enforce minimum? (1 second?)
3. **Wait precision:** How accurate are wait timings?
4. **Wait cancellation:** Can waits be cancelled externally?

## 16. Next Steps

- [ ] Implement wait() method
- [ ] Implement wait replay logic
- [ ] Add wait checkpoint
- [ ] Handle edge cases (zero, negative)
- [ ] Write wait tests
- [ ] Document wait limits
