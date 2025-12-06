# Lambda durable functions - Under the covers

This is a quick introduction to how the underlying API that the Lambda durable functions SDKs use works so that other SDKs can be built more easily.


## What is different about an invocation that is part of a durable execution vs a regular Lambda invocation?

When you `Invoke` a durable function, either in a synchronous (`RequestResponse`) or asynchronous (`Event`) manner, Lambda does not immediately start up an exectuion environment and begin executing your handler code. Instead, Lambda starts a "durable execution" that is used to orchestrate invoking your handler (potentially multiple times over the lifetime of the execution)

There are no differences in the [Lambda Runtime API](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html), extensions still work, and the same mechanism is used by the runtime to get the next invocation and to provide the invocation response, or error.

The major difference is in what the input payload for the invocation is, and what format the output payload must adhere to. Additionally, there are two new APIs (accessed through the public Lambda endpoint) that are used to retrieve execution state and checkpoint durable operation updates.


## Input Payload

The input payload is JSON of the following format:


```
{
    // The unique identifier for the durable execution. Required for calling the CheckpointDurableExecution and
    // GetDurableExecutionState APIs. Also useful for logging.
    "DurableExecutionArn": "...",
    // A checkpoint token. This is a required parameter for both the CheckpointDurableExecution and
    // GetDurableExecutionState APIs.
    "CheckpointToken": "...",
    // This will contain any existing state for the execution. Lambda will try to include as much state in this
    // as possible without exceeding the 6MB input payload limit.
    "InitialExecutionState": {
        // It is possible for this list to be empty but a NextMarker still be present. This means that
        // _none_ of the state fit (will only happen if the execution input is close to 6MB)
        "Operations": [
            // See https://docs.aws.amazon.com/lambda/latest/api/API_Operation.html
        ],
        // A pagination token - this can be used to call the GetDurableExecutionState API to retrieve the rest of
        // the state.
        "NextMarker": "..."
    }
}
```


The expectation is that upon receiving this, the handler checks to see if there is a `NextMarker` in the `InitialExecutionState` and immediately calls the `GetDurableExecutionState` API (passing in the marker in the `Marker` parameter) to retrieve the rest of the state (continue paginating until the response returns no `NextMarker`)


## Output Payload

The handler is required to return a JSON object of the following format:


```
{
    // Valid values are:
    //
    // - SUCCEEDED - This will make the execution complete with a SUCCEEDED status. A Result can be provided.
    //
    // - FAILED - This will make the exectuion complete with a FAILED status - An Error can be provided
    //
    // - PENDING - This will make the execution wait until a pending operation changes state.
    //             If PENDING status is returned when there are no pending operations, this is treated as an invalid response.
    "Status": "...",
    // The execution result (only valid for SUCCEEDED status)
    "Result": "...",
    // The error that caused the execution to fail (only valid for FAILED status)
    "Error":  {
        // See https://docs.aws.amazon.com/lambda/latest/api/API_ErrorObject.html
    }
}
```


You are permitted to return `PENDING` status if one or more of these hold across all operations in your execution state. Your handler will be reinvoked when any of the pending operations change status:

* There is a `STEP` currently in `PENDING` status
* There is a `WAIT` currently in `STARTED` status
* There is a `CALLBACK` currently in `STARTED` status
* There is a `CHAINED_INVOKE` currently in `STARTED` status

If the output format is not correct, or provides invalid combination of fields, Lambda treats this as a terminal state and the execution will fail.


### Examples

An extremely basic handler for a durable function that immediately succeeds is:


```
export const handler = async(event, context) => {
    return {
        "Status": "SUCCEEDED",
        "Result": JSON.stringify({"hello": "world"})
    };
}
```


When you invoke the function, the execution will succeed and the `Invoke` response payload will be


```
{
  "hello": "world"
}
```


Similarly with a handler like this:


```
export const handler = async(event, context) => {
    return {
        "Status": "FAILED",
        "Error": {
            "ErrorType": "MyError",
            "ErrorMessage": "Something went wrong"
        }
    };
}
```


When you invoke the function, the execution will fail and the `Invoke` response will have the `X-Amz-Function-Error` header set and the payload will be


```
{
  "errorMessage":"Something went wrong",
  "errorType":"MyError"
}
```



## Error Handling

There are 2 types of errors that can happen in the runtime and this is how Lambda handles them:


* Runtime Errors
    * These are errors prefixed with `Runtime`, `Sandbox`, and `Extension`, and generally happen outside the control of the handler function.
    * Lambda will retry up to 3 times in a row (with a short backoff between each) before failing the execution.
    * The only exception is `Sandbox.Timedout` which is treated like a Handler Error below.
* Handler Errors
    * Any other error thrown by the function
    * Lambda will retry (with exponential backoff and jitter) over a period of up to several hours before failing the execution (subject to the execution timeout configuration)
    * The expectation is that the handler will catch any terminal errors and return a `FAILED` status if the execution should fail. Any other failures are treated as retryable.

### Examples

#### Handler Error

```
export const handler = async(event, context) => {
    if (Math.random() < 0.75) {
        throw new Error("Random error");
    } else {
        return {
            "Status": "SUCCEEDED",
            "Result": JSON.stringify({"hello": "world"})
        };
    }
}
```


Invoking this function will eventually result in the execution succeeding after a few retries (unless it gets lucky on the first try)

The `Invoke` response payload will be:


```
{
  "hello": "world"
}
```


And the execution history will look something like (the number of failed invocations will depend on randomness):


```
ExecutionStarted
InvocationCompleted (Error = {"ErrorType": "Error", "ErrorMessage": "Random error"})
InvocationCompleted (Error = {"ErrorType": "Error", "ErrorMessage": "Random error"})
InvocationCompleted (Error = {"ErrorType": "Error", "ErrorMessage": "Random error"})
InvocationCompleted (No error)
ExecutionSucceeded (Result = {"hello": "world"})
```



### Runtime Error

This is a function with a syntax error outside of the handler.


```
not valid javascript;

export const handler = async(event, context) => {
    return {
        "Status": "SUCCEEDED",
        "Result": JSON.stringify({"hello": "world"})
    };
}
```


When you invoke the function, the execution will fail and the `Invoke` response will have the `X-Amz-Function-Error` header set and the payload will be


```
{
  "errorMessage":"SyntaxError: Unexpected identifier 'valid'",
  "errorType":"Runtime.UserCodeSyntaxError"
}
```


And the execution history will look like this:


```
ExecutionStarted
InvocationCompleted (Error)
InvocationCompleted (Error)
InvocationCompleted (Error)
InvocationCompleted (Error)
ExecutionFailed (Error = {"ErrorType":"Runtime.UserCodeSyntaxError", "ErrorMessage":"SyntaxError: Unexpected identifier 'valid'"})
```



## Execution State

>See https://docs.aws.amazon.com/lambda/latest/api/API_GetDurableExecutionState.html

See https://docs.aws.amazon.com/lambda/latest/api/API_Operation.html



The execution state contains the current state of each durable operation that has been created in the execution, returned in the order they were created in. There are 6 operation types:


* `CALLBACK` - Allows an execution to wait for external input without running waiting for it to finish
* `CHAINED_INVOKE` - Allows an execution to invoke another lambda function (durable or not) and suspend without running waiting for it to finish
* `CONTEXT` - Enables parent-child relationships between operations. Can be used to implement concurrency constructs as well as optimize replay of large executions.
* `EXECUTION` - This represents the execution itself
    * It will *always* be the first operation returned in the execution state.
* `STEP` - The core building block of durable functions. Supports storing the result of some operation as well as scheduling retries if failures occur
* `WAIT` - Allows an execution to wait for a period of time without running waiting for it to finish

### Operations

All operations have a common set of fields:


* `Id` - This is a unique identifier for the operation
* `ParentId` - The `Id` of the parent `CONTEXT` that this operation is associated with (only populated if associated with a parent)
* `Name` - This is a "friendly" name for the operation (doesn't have to be unique)
* `Type` - The type of operation (`EXECUTION`, `CONTEXT`, `STEP`, `WAIT`, `CALLBACK`, `CHAINED_INVOKE`)
* `SubType` - This is a "friendly" categorization field that helps SDKs create higher-level constructs on top of the primitive operations (for example concepts like `Map` and `Parallel`).
* `Status` The operation's status (`STARTED`, `PENDING`, `READY`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `TIMED_OUT`, `STOPPED`). Not all statuses are valid for all operation types.
* `StartTimestamp` - When the operation was created/started
* `EndTimestamp` - When the operation completed (only populated if it is completed)

Each type of operation also has an operation-specific details field that will be populated for operations of that type:

**CallbackDetails**


* `CallbackId` - The callback ID that can be used to call the `SendDurableExecutionCallbackSuccess`/`SendDurableExecutionCallbackFailure` / `SendDurableExecutionCallbackHeartbeat` APIs to manage the callback's lifecycle externally
* `Error` - If the status is `FAILED`, the error (if one was provided)
* `Result` - If the status is `SUCCEEDED`, the result (if one was provided)

**ChainedInvokeDetails**


* `Error` - If the status is `FAILED`, the error (if one was provided)
* `Result` - If the status is `SUCCEEDED` - the result (if one was provided)

**ContextDetails**


* `Error` - If the status is `FAILED`, the error (if one was provided)
* `Result` - If the status is `SUCCEEDED` - the result (if one was provided)
* `ReplayChildren` - If false, and the status is `FAILED` or `SUCCEEDED`, the operation information for any children of the context will *not* be included in the execution state (which can improve replay performance). If true, the child operations will be included. The `CONTEXT` can decide whether it wants to keep children for replay when a checkpoint to succeed or fail is done.

**ExecutionDetails**


* `InputPayload` - The original invoke input that started the execution

**StepDetails**


* `Error` - If the status is `FAILED`, the error (if one was provided)
* `Result` - If the status is `SUCCEEDED`, the result (if one was provided)
* `Attempt` - The current attempt number (starts at 1, incremented for each retry)
* `NextAttemptTimestamp` - When the next attempt is scheduled (only populated when the status is `PENDING`)

**WaitDetails**


* `ScheduledEndTimestamp` - When the wait operation is scheduled to complete.

## Checkpointing

>See https://docs.aws.amazon.com/lambda/latest/api/API_CheckpointDurableExecution.html

See https://docs.aws.amazon.com/lambda/latest/api/API_OperationUpdate.html


Up until now we've only seen an example of an executions succeeding or failing immediately. In order to persist state and start durable operations, the `CheckpointDurableExecution` API must be used.

This API can be used to start new operations, and complete existing ones. The response from the checkpoint API also includes an updates that happened to any operations since the last checkpoint call (e.g a `WAIT` completing)

It is valid to call the checkpoint API periodically with no updates, just to receive the latest state. This is useful if for example there is a long-running operation (e.g. for a `STEP`) happening but you want to check if a `WAIT`/`CALLBACK`/`CHAINED_INVOKE` has completed.


### Checkpoint Tokens

You may have noticed that the input payload contains a `CheckpointToken`. This is a *one-time-use* token that can be used to checkpoint updates to one or more operations (the same token can be used multiple times for `GetDurableExecutionState`, but it must be the *current* valid token used).

When a checkpoint operation succeeds, the response contains a new `CheckpointToken`. Re-using a checkpoint token that has already been used, or one from an invocation/execution that has already completed will result in the call failing with an `InvalidParameterValueException` error with a `Invalid checkpoint token` message.

The reason this token exists is to ensure consistency and to avoid multiple checkpoint calls from all mutating state concurrently without having seen the current up to date execution state. Think of it like a conditional-write or a compare-and-set operation.


### Operation Updates

All operation updates have a common set of fields:


* `Id` - This is a unique identifier for the operation
* `ParentId` - The `Id` of the parent `CONTEXT` that this operation is associated with. If specified, this *must* refer to an existing `CONTEXT` (either earlier in the same checkpoint, or checkpointed an an earlier call)
* `Name` - This is an optional "friendly" name for the operation (doesn't have to be unique)
* `Type` - The type of operation (`EXECUTION`, `CONTEXT`, `STEP`, `WAIT`, `CALLBACK`, `CHAINED_INVOKE`)
* `SubType` - This is an optional "friendly" categorization field that helps SDKs create higher-level constructs on top of the primitive operations (for example concepts like `Map` and `Parallel`).
* `Error` - The error associate with the failure action
* `Payload` - The payload associated with the successful action
* `Action` - The action to take (`START`, `SUCCEED`, `FAIL`, `RETRY`, `CANCEL`) - Not all actions can be taken on all operations in all statuses.

If an operation already exists, you do not have to pass in `ParentId`,`Name`,`SubType` again - but if you do, they have to match what exists in the current state.

Each type of operation has a different set of statuses it can be in and actions that can be taken on it in each status. See the state diagrams below. If an invalid action is taken, the checkpoint call will fail with an `InvalidParameterValueException`.

Each type of operation also has an operation-specific options field with parameters specific to that operation.

**CALLBACK**

State Diagram:
[Image: image.png]
```
stateDiagram-v2
    [*] --> STARTED : START action
    STARTED --> SUCCEEDED : SendDurableExecutionCallbackSuccess
    STARTED --> FAILED : SendDurableExecutionCallbackFailure
    STARTED --> TIMED_OUT : Timeout or HeartbeatTimeout
    SUCCEEDED --> [*]
    FAILED --> [*]
    TIMED_OUT --> [*]
```


The only valid action for `CALLBACK` is `START`

Example `OperationUpdate`

```
{
    "Id": "callback1",
    "Type": "CALLBACK",
    "Action": "START",
    "CallbackOptions": {
        // Optional, Callback has to be completed in this amount of time
        "TimeoutSeconds": 3600,
        // Optional, SendDurableExecutionCallbackHeartbeat has to be called
        // periodically within this time frame
        "HeartbeatTimeoutSeconds": 300
    }
}
```


**CHAINED_INVOKE**

State Diagram
[Image: image.png]

```
stateDiagram-v2
    [*] --> STARTED : START action
    STARTED --> SUCCEEDED : Invoke succeeded
    STARTED --> FAILED : Invoke failed
    STARTED --> TIMED_OUT : Invoke timed out
    STARTED --> STOPPED : Invoke was stopped
    SUCCEEDED --> [*]
    FAILED --> [*]
    TIMED_OUT --> [*]
    STOPPED --> [*]
```


A `CHAINED_INVOKE` can invoke both durable and non-durable functions.

The only valid action for `CHAINED_INVOKE` is `START`

Example `OperationUpdate`


```
{
    "Id": "chain1",
    "Type": "CHAINED_INVOKE",
    "Action": "START",
    // The input payload to the invoked function
    "Payload": "{\"hello\":\"world\"}",
    "CallbackOptions": {
        // The name or ARN of the Lambda function to invoke
        // A `CHAINED_INVOKE` can invoke both durable and non-durable functions. Chains of
        // durable functions can run for up to 1 year
        "FunctionName": "...",
        // Optional, the tenant id to use if the target function is using tenant isolution
        "TenantId": "..."
    }
}
```


**CONTEXT**

State Diagram
[Image: image.png]

```
stateDiagram-v2
    [*] --> STARTED : START action
    STARTED --> SUCCEEDED : SUCCEED action
    STARTED --> FAILED : FAIL action
    SUCCEEDED --> [*]
    FAILED --> [*]
```


There are 3 valid actions for `CONTEXT`: `START`, `SUCCEED`, `FAIL`

Example `OperationUpdate`s

Starting a context


```
{
    "Id": "context1",
    "Type": "CONTEXT",
    "Action": "START"
}
```


Successful context (include any children in future invocations' execution state)


```
{
    "Id": "context1",
    "Type": "CONTEXT",
    "Action": "SUCCEED",
    "Payload": "\"it worked\"",
    "ContextOptions": {
        "ReplayChildren": true
    }
}
```


Failed context (default do not include any children in future invocations' execution state)


```
{
    "Id": "context1",
    "Type": "CONTEXT",
    "Action": "FAIL",
    "Error": {
        "ErrorType": "MyType",
        "ErrorMessage": "Something broke"
    }
}
```


**EXECUTION**

This can be used to complete the execution (as an alternative to returning the response from the invocation output). This should be used when the size of the output payload is very close to 6MB.

After checkpointing this operation, no further checkpoints can be done and the execution will complete. You should immediately return from the handler (with no output payload). No new `CheckpointToken` will be returned in the response. If checkpointing multiple items, this one must be the last in the list of `Updates`

There are 2 valid actions for `EXECUTION`: `SUCCEED` and `FAIL`

State Diagram


```
stateDiagram-v2
    STARTED --> SUCCEEDED : SUCCEED action
    STARTED --> FAILED : FAIL action
    SUCCEEDED --> [*]
    FAILED --> [*]
```


Success


```
{
    "Id": "<the id from the EXECUTION operation in the execution state>",
    "Type": "EXECUTION",
    "Action": "SUCCEED",
    "Payload": "\"it worked\""
}
```


Failure


```
{
    "Id": "<the id from the EXECUTION operation in the execution state>",
    "Type": "EXECUTION",
    "Action": "FAIL",
    "Error": {
        "ErrorType": "MyType",
        "ErrorMessage": "Something broke"
    }
}
```


**STEP**

State Diagram


```
stateDiagram-v2
    [*] --> STARTED : START action
    STARTED --> PENDING : RETRY action
    PENDING --> READY : Backoff completed
    READY --> STARTED : START action
    STARTED --> SUCCEEDED : SUCCEED action
    STARTED --> FAILED : FAIL action
    SUCCEEDED --> [*]
    FAILED --> [*]
```


`STEP` has a built-in retry mechanism to allow multiple attempts without always loading all of the state for each attempt. Only the Result/Error for the latest attempt is included in the execution state (but all attempts are in the execution history)

There are 4 valid actions for `STEP`: `START`, `SUCCEED`, `FAIL`, `RETRY`

Start

```
{
    "Id": "step1",
    "Type": "STEP",
    "Action": "START"
}
```


Success


```
{
    "Id": "step1",
    "Type": "STEP",
    "Action": "SUCCEED",
    "Payload": "\"it worked\""
}
```


Failure


```
{
    "Id": "step1",
    "Type": "STEP",
    "Action": "FAIL",
    "Error": {
        "ErrorType": "MyType",
        "ErrorMessage": "Something broke"
    }
}
```


Retry (on failure)


```
{
    "Id": "step1",
    "Type": "STEP",
    "Action": "FAIL",
    "Error": {
        "ErrorType": "MyType",
        "ErrorMessage": "Something broke"
    },
    "StepOptions": {
        "NextAttemptDelaySeconds": 10
    }
}
```

Retry (on success - can be used for polling loops)



```
{
    "Id": "step1",
    "Type": "STEP",
    "Action": "RETRY",
    "Payload": "\"state for next attempt\"",
    "StepOptions": {
        "NextAttemptDelaySeconds": 10
    }
}
```


**WAIT**

State Diagram


```
stateDiagram-v2
    [*] --> STARTED : START action
    STARTED --> SUCCEEDED : Wait time ended
    STARTED --> CANCELLED : CANCEL action
    SUCCEEDED --> [*]
    CANCELLED --> [*]
```


There are 2 valid actions for `WAIT`: `START` and `CANCEL`

Start


```
{
    "Id": "wait1",
    "Type": "WAIT",
    "Action": "START",
    "WaitOptions": {
        "WaitSeconds": 5
    }
}
```


Cancel


```
{
    "Id": "wait1",
    "Type": "WAIT",
    "Action": "CANCEL"
}
```



### Examples

Here's a handler that does a 5 second wait, then starts a callback (you can complete via the console), then completes the execution with the output (or error) of the callback.

It's roughly equivalent to the following code that uses the SDK:


```
import {withDurableExecution} from "@aws/durable-execution-sdk-js";

export const handler = withDurableExecution(async (event, context) => {
    await context.wait({seconds: 5});
    const [callbackPromise, callbackId] = await context.createCallback();
    context.logger.info(callbackId);
    return await callbackPromise;
});
```


Look, no Durable SDK, just the Lambda API!


```
import {
  LambdaClient,
  CheckpointDurableExecutionCommand,
  GetDurableExecutionStateCommand
} from "@aws-sdk/client-lambda";

const lambdaClient = new LambdaClient();

// Initialize the execution state from the event, paginating if necessary
async function initializeExecutionState(event) {
    const operationsArray = event.InitialExecutionState.Operations;
    let nextMarker = event.InitialExecutionState.NextMarker;
    while (nextMarker != null) {
        const stateResponse = await lambdaClient.send(
            new GetDurableExecutionStateCommand({
                CheckpointToken: event.CheckpointToken,
                DurableExecutionArn: event.DurableExecutionArn,
                Marker: nextMarker
            })
        );
        operationsList.push(...stateResponse.Operations);
        nextMarker = stateResponse.NextMarker;
    }

    // Make a mapping of operation id to operation
    const operations = operationsArray.reduce((acc, op) => {
        acc[op.Id] = op;
        return acc;
    }, {});

    return operations;
}

// Perform a checkpoint
async function doCheckpoint(checkpointToken, durableExecutionArn, updates) {
    const checkpointResponse = await lambdaClient.send(
        new CheckpointDurableExecutionCommand({
            CheckpointToken: checkpointToken,
            DurableExecutionArn: durableExecutionArn,
            Updates: updates
        })
    );

    return {
        newCheckpointToken: checkpointResponse.CheckpointToken,
        updatedOperations: checkpointResponse.NewExecutionState.Operations
    };
}

// Apply updated state from a checkpoint response into the current state
function applyNewState(existingOperations, updatedOperations) {
    return updatedOperations.reduce((acc, op) => {
        acc[op.Id] = op;
        return acc;
    }, existingOperations);
}

const WAIT_OPERATION_ID = 'my-wait';
const CALLBACK_OPERATION_ID = 'my-callback';

export const handler = async(event, context) => {
    try {
        const durableExecutionArn = event.DurableExecutionArn;
        let checkpointToken = event.CheckpointToken;

        const operations = await initializeExecutionState(event);

        // A little state machine that handles starting a WAIT and waiting for it,
        // then starting a CALLBACK and waiting for it and completing the execution
        // with the result of the callback.

        let isPending = false;
        let state = 1;
        while (!isPending) {
            switch (state) {
                // Starting the wait
                case 1:
                    if (!operations[WAIT_OPERATION_ID]) {
                        const res = await doCheckpoint(
                            checkpointToken,
                            durableExecutionArn,
                            [{
                                Id: WAIT_OPERATION_ID,
                                Type: 'WAIT',
                                Action: 'START',
                                WaitOptions: {
                                    WaitSeconds: 5
                                }
                            }]
                        );
                        applyNewState(operations, res.updatedOperations);
                        checkpointToken = res.newCheckpointToken;
                    }
                    state = 2;
                    break;
                // Waiting for the wait
                case 2:
                    if (operations[WAIT_OPERATION_ID].Status === 'STARTED') {
                        isPending = true;
                    } else {
                        // The wait is done
                        state = 3;
                    }
                    break;
                // Starting the callback
                case 3:
                    if (!operations[CALLBACK_OPERATION_ID]) {
                        const res = await doCheckpoint(
                            checkpointToken,
                            durableExecutionArn,
                            [{
                                Id: CALLBACK_OPERATION_ID,
                                Type: 'CALLBACK',
                                Action: 'START'
                            }]
                        );
                        applyNewState(operations, res.updatedOperations);
                        checkpointToken = res.newCheckpointToken;

                        // Log the callback id
                        console.log(operations[CALLBACK_OPERATION_ID].CallbackDetails.CallbackId);
                    }
                    state = 4;
                    break;
                // Waiting for the callback
                case 4:
                    if (operations[CALLBACK_OPERATION_ID].Status === 'STARTED') {
                        isPending = true;
                    } else {
                        // The callback is done
                        state = 5;
                    }
                    break;
                case 5:
                    // The execution is complete
                    const callbackOp = operations[CALLBACK_OPERATION_ID];
                    return {
                        Status: callbackOp.Status === 'SUCCEEDED' ? 'SUCCEEDED' : 'FAILED',
                        Result: callbackOp.CallbackDetails.Result,
                        Error: callbackOp.CallbackDetails.Error
                    };
            }
        }

        return {
            Status: "PENDING"
        };
    } catch (e) {
        // Fail the execution if anything goes wrong
        return {
            "Status": "FAILED",
            "Error": {
                "ErrorMessage": e.message
            }
        };
    }
}
```



### Next Steps

As you can see, interacting manually with the API and tracking the state can get complicated the more complex the set of operations are. This is why we have built reusable logic and higher-level abstractions into our SDKs.

However, using this basic information about how to interact with the API, you should be able to start writing your own SDK in whatever language you like!
