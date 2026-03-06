## Error Handling

The SDK throws specific exceptions to help you handle different failure scenarios:

```
DurableExecutionException              - General durable exception
├── NonDeterministicExecutionException - Code changed between original execution and replay. Fix code to maintain determinism; don't change step order/names.
├── SerDesException                    - Serialization and deserialization exception.
└── DurableOperationException          - General operation exception
    ├── StepException                  - General Step exception
    │   ├── StepFailedException        - Step exhausted all retry attempts.Catch to implement fallback logic or let execution fail.
    │   └── StepInterruptedException   - `AT_MOST_ONCE` step was interrupted before completion. Implement manual recovery (check if operation completed externally)
    ├── InvokeException                - General chained invocation exception
    │   ├── InvokeFailedException      - Chained invocation failed. Handle the error or propagate failure.
    │   ├── InvokeTimedoutException    - Chained invocation timed out. Handle the error or propagate failure.
    │   └── InvokeStoppedException     - Chained invocation stopped. Handle the error or propagate failure.
    ├── CallbackException              - General callback exception
    │   ├── CallbackFailedException    - External system sent an error response to the callback. Handle the error or propagate failure
    │   └── CallbackTimeoutException   - Callback exceeded its timeout duration. Handle the error or propagate the failure
    └── ChildContextFailedException    - Child context failed and the original exception could not be reconstructed
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