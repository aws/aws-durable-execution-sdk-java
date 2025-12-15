# AWS Lambda Durable Execution Java SDK - Sequence Diagram

## Normal Execution Flow

```mermaid
sequenceDiagram
    participant LR as Lambda Runtime
    participant DH as DurableHandler
    participant DE as DurableExecution
    participant ES as ExecutionState
    participant CM as CheckpointManager
    participant DC as DurableContext
    participant UC as User Code
    participant API as AWS API

    LR->>DH: handleRequest(input, context)
    DH->>DH: Parse DurableExecutionInput
    DH->>DE: execute(input, context, inputType, handler)
    
    DE->>API: loadAllOperations() [if pagination needed]
    API-->>DE: operations
    DE->>ES: new ExecutionState(arn, token, operations)
    DE->>CM: new CheckpointManager(state, client)
    DE->>DC: new DurableContext(checkpointManager, serDes)
    
    DE->>UC: handler.apply(userInput, context)
    
    loop For each operation
        UC->>DC: step(name, type, func)
        DC->>ES: getOperation(operationId)
        
        alt Operation exists (replay)
            ES-->>DC: existing operation
            DC-->>UC: cached result
        else New operation
            DC->>UC: func.get()
            UC-->>DC: result
            DC->>CM: checkpoint(operationUpdate)
            CM->>API: CheckpointDurableExecution
            API-->>CM: new checkpoint token
            CM->>ES: updateState(newToken, operation)
            CM-->>DC: completion future
            DC-->>UC: result
        end
    end
    
    alt Wait operation
        UC->>DC: wait(duration)
        DC->>CM: checkpoint(WAIT operation)
        CM->>API: CheckpointDurableExecution
        API-->>CM: new checkpoint token
        DC->>DC: throw SuspendExecutionException
        DC-->>DE: SuspendExecutionException
        DE-->>DH: DurableExecutionOutput.pending()
    else Normal completion
        UC-->>DE: result
        DE-->>DH: DurableExecutionOutput.success(result)
    else Exception
        UC-->>DE: exception
        DE-->>DH: DurableExecutionOutput.failure(error)
    end
    
    DH-->>LR: JSON response
```

## Checkpoint Batching Detail

```mermaid
sequenceDiagram
    participant DC as DurableContext
    participant CM as CheckpointManager
    participant Q as Queue
    participant E as Executor
    participant API as AWS API
    participant ES as ExecutionState

    DC->>CM: checkpoint(operationUpdate)
    CM->>Q: enqueue(CheckpointRequest)
    CM->>CM: tryStartProcessing()
    
    alt Processing not active
        CM->>E: submit(processBatch)
        
        loop Batch processing
            E->>Q: poll requests (up to 750KB)
            E->>API: CheckpointDurableExecution(batch)
            API-->>E: response with new token
            E->>ES: updateState(newToken, operations)
            E->>E: complete all futures in batch
        end
        
        E-->>CM: batch complete
    end
    
    CM-->>DC: CompletableFuture<Void>
```

## Replay Scenario

```mermaid
sequenceDiagram
    participant LR as Lambda Runtime
    participant DH as DurableHandler
    participant DE as DurableExecution
    participant ES as ExecutionState
    participant DC as DurableContext
    participant UC as User Code

    Note over LR,UC: Lambda resumes after interruption
    
    LR->>DH: handleRequest(input with existing state)
    DH->>DE: execute(input, context, inputType, handler)
    DE->>ES: new ExecutionState(arn, token, existingOperations)
    DE->>DC: new DurableContext(checkpointManager, serDes)
    
    DE->>UC: handler.apply(userInput, context)
    
    Note over UC,ES: Replay completed operations
    UC->>DC: step("step1", String.class, func1)
    DC->>ES: getOperation("1")
    ES-->>DC: existing operation (SUCCEEDED)
    DC-->>UC: cached result from step1
    
    UC->>DC: step("step2", String.class, func2)
    DC->>ES: getOperation("2")
    ES-->>DC: existing operation (SUCCEEDED)
    DC-->>UC: cached result from step2
    
    Note over UC,ES: Continue with new operations
    UC->>DC: step("step3", String.class, func3)
    DC->>ES: getOperation("3")
    ES-->>DC: null (new operation)
    DC->>UC: func3.get()
    UC-->>DC: new result
    Note over DC: Checkpoint new operation...
    
    UC-->>DE: final result
    DE-->>DH: DurableExecutionOutput.success(result)
    DH-->>LR: JSON response
```

## Error Handling Flow

```mermaid
sequenceDiagram
    participant UC as User Code
    participant DC as DurableContext
    participant CM as CheckpointManager
    participant DE as DurableExecution
    participant DH as DurableHandler
    participant API as AWS API

    UC->>DC: step("failing-step", String.class, func)
    DC->>UC: func.get()
    UC-->>DC: throws Exception
    
    alt Retryable error
        DC->>CM: checkpoint(RETRY operation)
        CM->>API: CheckpointDurableExecution
        API-->>CM: success
        DC->>DC: throw SuspendExecutionException
        DC-->>DE: SuspendExecutionException
        DE-->>DH: DurableExecutionOutput.pending()
    else Terminal error
        DC-->>DE: Exception propagates
        DE->>DE: catch Exception
        DE-->>DH: DurableExecutionOutput.failure(ErrorObject)
    end
    
    alt API error during checkpoint
        CM->>API: CheckpointDurableExecution
        API-->>CM: API Exception
        CM->>CM: retry with backoff
        
        alt Retry succeeds
            CM->>API: CheckpointDurableExecution
            API-->>CM: success
        else Retry exhausted
            CM-->>DC: CompletableFuture.completeExceptionally()
            DC-->>DE: Exception
            DE-->>DH: DurableExecutionOutput.failure(ErrorObject)
        end
    end
```

## Key Interactions

### 1. **Initialization Phase**
- DurableExecution loads all operations (with pagination if needed)
- Creates ExecutionState with operation lookup map
- Initializes CheckpointManager with async executor

### 2. **Operation Execution**
- DurableContext checks ExecutionState for existing operations
- New operations execute user code and checkpoint results
- Replay operations return cached results without re-execution

### 3. **Checkpoint Batching**
- CheckpointManager queues requests and batches API calls
- Maintains 750KB size limit per batch
- Updates ExecutionState with new tokens atomically

### 4. **Suspension Handling**
- Wait operations checkpoint and throw SuspendExecutionException
- Exception propagates to DurableExecution
- Returns PENDING status to Lambda runtime

### 5. **Error Recovery**
- User code exceptions can trigger retries or terminal failure
- API errors are retried with exponential backoff
- Replay ensures progress is never lost
