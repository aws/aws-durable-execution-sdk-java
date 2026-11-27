# ADR-006: AT_LEAST_ONCE_ASYNC steps complete before SUCCEED is saved

**Status:** Proposed. Amends [ADR-003](003-completable-future-based-coordination.md).

**Date:** 2026-11-27

## Context

1. Under ADR-003, a step waits for the service to answer its SUCCEED checkpoint. Then the SDK
   thread completes the step's `CompletableFuture`.
2. So `get()` returns a step result only after the service saved it. The `onOperationEnd` hook
   also fires only after the save.
3. Each SUCCEED costs one checkpoint round trip before the code after the step can run.
4. For an idempotent step, the caller can accept that the step runs again when its result was not
   saved.

## Decision

A step with `StepSemantics.AT_LEAST_ONCE_ASYNC` changes the step completion of ADR-003:

1. The step thread queues the SUCCEED checkpoint and does not wait for the answer.
2. The step thread then completes the `CompletableFuture`. This registers and unblocks the
   context threads that wait for the step, as the SDK thread does under ADR-003.
3. `get()` returns the result that the step kept in memory. Storage may not hold it yet.
4. The `onOperationEnd` hook fires when the service answers the SUCCEED, as for `AT_LEAST_ONCE`.
   The invocation drains its checkpoints before it fires `onInvocationEnd`. So the step end hook
   fires before the invocation end hook. A failed SUCCEED fires no end hook with status
   `SUCCEEDED`.

A failed attempt still waits for its RETRY or FAIL checkpoint. The other semantics keep ADR-003
unchanged.

The step is still executed at least once, for these reasons:

1. Checkpoints are sent in order. So a later checkpoint that the service accepts also saves the
   SUCCEED.
2. The queued SUCCEED counts as a checkpoint request in flight. So the invocation does not suspend
   before the service answers it.
3. The invocation drains its checkpoints before it reports an outcome.
4. If the SUCCEED fails, the invocation ends with that failure. Another failure, such as a failed
   checkpoint of an unawaited step, can end the invocation first. Then the SUCCEED is not sent,
   and the invocation ends with that first failure. In both cases it does not report the handler
   result or the handler failure. So the step runs again on replay.

## Consequences

- The code after the step runs one checkpoint round trip earlier.
- The code after the step can run before the result is saved. If the save fails, the step and
  that code run again on replay, and the step can return a different value.
- A plugin sees the step end after the code after the step started. The end hook reports the
  saved operation, as for `AT_LEAST_ONCE`.
- `AT_LEAST_ONCE_ASYNC` is experimental.

## Enforcement

`StepAtLeastOnceAsyncIntegrationTest` covers these cases:

- the step returns, also with a null result, while the SUCCEED is unanswered;
- the end hook fires once when the SUCCEED is answered, before the invocation end hook;
- a failed SUCCEED fires no end hook with status `SUCCEEDED`;
- the invocation does not suspend or report an outcome before the SUCCEED is saved;
- a failed SUCCEED ends the invocation, and the step runs again on replay;
- a failed checkpoint of an unawaited step ends the invocation before the SUCCEED is sent.
