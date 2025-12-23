# AWS Lambda Durable Execution Java SDK - Sequence Diagram

## Normal Execution Flow

```mermaid
sequenceDiagram
    participant LR as Lambda Runtime
    participant DH as DurableHandler
    participant DE as DurableExecutor
    participant EM as ExecutionManager
    participant DC as DurableContext
    participant UC as User Code
    participant DAR as DAR Backend

    LR->>DH: handleRequest(input, context)
    DH->>DH: Parse DurableExecutionInput
    DH->>DE: execute(input, context, inputType, handler)
    
    DE->>DAR: loadAllOperations() [if pagination needed]
    DAR-->>DE: operations
    DE->>EM: new ExecutionManager(arn, token, operations, client)
    DE->>DC: new DurableContext(executionManager, serDes)
    
    DE->>UC: handler.apply(userInput, context)
    
    loop For each operation
        UC->>DC: step(name, type, func)
        DC->>EM: getOperation(operationId)
        
        alt Operation exists (replay)
            EM-->>DC: existing operation
            DC-->>UC: cached result
        else New operation
            DC->>UC: func.get()
            UC-->>DC: result
            DC->>EM: checkpoint(operationUpdate)
            EM->>DAR: CheckpointDurableExecution
            DAR-->>EM: new checkpoint token
            EM->>EM: updateState(newToken, operation)
            EM-->>DC: completion future
            DC-->>UC: result
        end
    end
    
    alt Wait operation
        UC->>DC: wait(duration)
        DC->>EM: checkpoint(WAIT operation)
        EM->>DAR: CheckpointDurableExecution
        DAR-->>EM: new checkpoint token
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
    participant EM as ExecutionManager
    participant CB as CheckpointBatcher
    participant Q as Queue
    participant E as Executor
    participant DAR as DAR Backend

    DC->>EM: checkpoint(operationUpdate)
    EM->>CB: checkpoint(operationUpdate)
    CB->>Q: enqueue(CheckpointRequest)
    CB->>CB: tryStartProcessing()
    
    alt Processing not active
        CB->>E: submit(processBatch)
        
        loop Batch processing
            E->>Q: poll requests (up to 750KB)
            E->>DAR: CheckpointDurableExecution(batch)
            DAR-->>E: response with new token
            E->>EM: updateState(newToken, operations)
            E->>E: complete all futures in batch
        end
        
        E-->>CB: batch complete
    end
    
    CB-->>EM: CompletableFuture<Void>
    EM-->>DC: CompletableFuture<Void>
```

## Replay Scenario

```mermaid
sequenceDiagram
    participant LR as Lambda Runtime
    participant DH as DurableHandler
    participant DE as DurableExecutor
    participant EM as ExecutionManager
    participant DC as DurableContext
    participant UC as User Code

    Note over LR,UC: Lambda resumes after interruption
    
    LR->>DH: handleRequest(input with existing state)
    DH->>DE: execute(input, context, inputType, handler)
    DE->>EM: new ExecutionManager(arn, token, existingOperations, client)
    DE->>DC: new DurableContext(executionManager, serDes)
    
    DE->>UC: handler.apply(userInput, context)
    
    Note over UC,EM: Replay completed operations
    UC->>DC: step("step1", String.class, func1)
    DC->>EM: getOperation("1")
    EM-->>DC: existing operation (SUCCEEDED)
    DC-->>UC: cached result from step1
    
    UC->>DC: step("step2", String.class, func2)
    DC->>EM: getOperation("2")
    EM-->>DC: existing operation (SUCCEEDED)
    DC-->>UC: cached result from step2
    
    Note over UC,EM: Continue with new operations
    UC->>DC: step("step3", String.class, func3)
    DC->>EM: getOperation("3")
    EM-->>DC: null (new operation)
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
    participant CB as CheckpointBatcher
    participant DE as DurableExecutor
    participant DH as DurableHandler
    participant DAR as DAR Backend

    UC->>DC: step("failing-step", String.class, func)
    DC->>UC: func.get()
    UC-->>DC: throws Exception
    
    alt Retryable error
        DC->>CB: checkpoint(RETRY operation)
        CB->>DAR: CheckpointDurableExecution
        DAR-->>CB: success
        DC->>DC: throw SuspendExecutionException
        DC-->>DE: SuspendExecutionException
        DE-->>DH: DurableExecutionOutput.pending()
    else Terminal error
        DC-->>DE: Exception propagates
        DE->>DE: catch Exception
        DE-->>DH: DurableExecutionOutput.failure(ErrorObject)
    end
    
    alt API error during checkpoint
        CB->>DAR: CheckpointDurableExecution
        DAR-->>CB: API Exception
        CB->>CB: retry with backoff
        
        alt Retry succeeds
            CB->>DAR: CheckpointDurableExecution
            DAR-->>CB: success
        else Retry exhausted
            CB-->>DC: CompletableFuture.completeExceptionally()
            DC-->>DE: Exception
            DE-->>DH: DurableExecutionOutput.failure(ErrorObject)
        end
    end
```

## Key Interactions

### 1. **Initialization Phase**
- DurableExecutor loads all operations (with pagination if needed)
- Creates ExecutionState with operation lookup map
- Initializes CheckpointBatcher with async executor
- Creates ExecutionManager for coordination

### 2. **Operation Execution**
- DurableContext checks ExecutionManager for existing operations
- New operations execute user code and checkpoint results
- Replay operations return cached results without re-execution

### 3. **Checkpoint Batching**
- CheckpointBatcher queues requests and batches API calls
- Maintains 750KB size limit per batch
- Updates ExecutionState with new tokens atomically

### 4. **Suspension Handling**
- Wait operations checkpoint and throw SuspendExecutionException
- Exception propagates to DurableExecutor
- Returns PENDING status to Lambda runtime

### 5. **Error Recovery**
- User code exceptions can trigger retries or terminal failure
- API errors are retried with exponential backoff
- Replay ensures progress is never lost
