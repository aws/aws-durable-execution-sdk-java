## Error Handling

The SDK throws specific exceptions to help you handle different failure scenarios:

```
RuntimeException
├── SuspendExecutionException          - Internal control-flow exception thrown by the SDK to suspend execution
│                                        (e.g., during wait(), waitForCallback(), waitForCondition()).
│                                        The SDK catches this internally — you will never see it unless you have
│                                        a broad catch(Exception) block around durable operations. If caught
│                                        accidentally, you MUST re-throw it so the SDK can suspend correctly.
│
└── DurableExecutionException              - General durable exception
    ├── SerDesException                    - Serialization and deserialization exception.
    ├── UnrecoverableDurableExecutionException - Execution cannot be recovered. The durable execution will be immediately terminated.
    │   ├── NonDeterministicExecutionException - Code changed between original execution and replay. Fix code to maintain determinism; don't change step order/names.
    │   └── IllegalDurableOperationException   - An illegal operation was detected. The execution will be immediately terminated.
    └── DurableOperationException          - General operation exception
        ├── StepException                  - General Step exception
        │   ├── StepFailedException        - Step exhausted all retry attempts. Catch to implement fallback logic or let execution fail.
        │   └── StepInterruptedException   - `AT_MOST_ONCE` step was interrupted before completion. Implement manual recovery (check if operation completed externally)
        ├── InvokeException                - General chained invocation exception
        │   ├── InvokeFailedException      - Chained invocation failed. Handle the error or propagate failure.
        │   ├── InvokeTimedOutException    - Chained invocation timed out. Handle the error or propagate failure.
        │   └── InvokeStoppedException     - Chained invocation stopped. Handle the error or propagate failure.
        ├── CallbackException              - General callback exception
        │   ├── CallbackFailedException    - External system sent an error response to the callback. Handle the error or propagate failure
        │   ├── CallbackTimeoutException   - Callback exceeded its timeout duration. Handle the error or propagate the failure
        │   └── CallbackSubmitterException - Submitter step failed to submit the callback. Handle the error or propagate failure
        ├── WaitForConditionFailedException- waitForCondition exceeded max polling attempts or failed. Catch to implement fallback logic.
        ├── ChildContextFailedException    - Child context failed and the original exception could not be reconstructed
        ├── MapIterationFailedException    - Map iteration failed and the original exception could not be reconstructed
        └── ParallelBranchFailedException  - Parallel branch failed and the original exception could not be reconstructed
```

```java
try {
    var result = ctx.step("charge-payment", Payment.class,
        stepCtx -> paymentService.charge(amount),
        StepConfig.builder()
            .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
            .build());
} catch (StepInterruptedException e) {
    // Step started but we don't know if it completed
    // Check payment status externally before retrying
    var status = paymentService.checkStatus(transactionId);
    if (status.isPending()) {
        throw e; // Let it fail - manual intervention needed
    }
}
```

### Handling SuspendExecutionException

If you have a broad `catch (Exception e)` block around durable operations, you must re-throw `SuspendExecutionException` to let the SDK suspend correctly:

```java
try {
    ctx.step("work", String.class, stepCtx -> doWork());
    ctx.wait("pause", Duration.ofDays(1));
    ctx.step("more-work", String.class, stepCtx -> doMoreWork());
} catch (SuspendExecutionException e) {
    throw e; // Always re-throw — lets the SDK suspend the execution
} catch (Exception e) {
    log.error("Operation failed", e);
}
```