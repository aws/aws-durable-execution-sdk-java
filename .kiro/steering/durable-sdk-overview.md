# AWS Lambda Durable Execution SDK Documentation

**Source:** AWS Lambda Developer Guide & GitHub Repositories  
**Last Retrieved:** December 2, 2025

## Overview

The AWS Durable Execution SDK provides the foundation for building durable Lambda functions. It abstracts the complexity of checkpoint management and replay, enabling developers to write sequential code that automatically becomes fault-tolerant.

### Available SDKs

- **JavaScript/TypeScript:** [@aws/durable-execution-sdk-js](https://github.com/aws/aws-durable-execution-sdk-js)
- **Python:** [aws-durable-execution-sdk-python](https://github.com/aws/aws-durable-execution-sdk-python)

Both SDKs are open source under Apache-2.0 license.

## Installation

### JavaScript/TypeScript

```bash
npm install @aws/durable-execution-sdk-js
```

### Python

```bash
pip install aws-durable-execution-sdk-python
```

## Core Architecture

### SDK Responsibilities

The SDK handles three critical responsibilities:

1. **Checkpoint Management:** Automatically creates and persists checkpoints as operations execute
2. **Replay Coordination:** Ensures deterministic replay by skipping completed operations and using stored results
3. **State Isolation:** Maintains execution state separately from business logic with encryption at rest

### How Checkpointing Works

When you call a durable operation, the SDK follows this sequence:

1. **Check for existing checkpoint:** Verifies if operation already completed in a previous invocation
2. **Execute the operation:** If no checkpoint exists, executes your operation code
3. **Create checkpoint:** Serializes the result and creates a checkpoint with operation metadata
4. **Persist checkpoint:** Calls Lambda checkpoint API to ensure durability
5. **Return result:** Returns the operation result to continue execution

### Replay Behavior

When a function resumes after a pause or interruption:

1. **Load checkpoint log:** Retrieves the checkpoint log for this execution
2. **Run from beginning:** Invokes handler function from the start
3. **Skip completed operations:** Returns stored results for completed operations without re-executing
4. **Resume at interruption point:** Executes normally when reaching an operation without a checkpoint

**Critical Requirement:** Code must be deterministic during replay. Given the same inputs and checkpoint log, the function must make the same sequence of durable operation calls.

## DurableContext

The `DurableContext` object replaces the standard Lambda context and provides methods for all durable operations.

### TypeScript Setup

```typescript
import { withDurableExecution, DurableContext } from '@aws/durable-execution-sdk-js';

export const handler = withDurableExecution(
  async (event: any, context: DurableContext) => {
    // Your function receives DurableContext instead of Lambda context
    // Use context.step(), context.wait(), etc.
    return result;
  }
);
```

### Python Setup

```python
from aws_durable_execution_sdk_python import durable_execution, DurableContext

@durable_execution
def handler(event: dict, context: DurableContext):
    # Your function receives DurableContext
    # Use context.step(), context.wait(), etc.
    return result
```

## Durable Operations

### Steps

Executes business logic with automatic checkpointing and retry. Each step saves its result, ensuring the function can resume from any completed step.

**TypeScript:**
```typescript
const result = await context.step('process-payment', async () => {
  return await paymentService.charge(amount);
});
```

**Python:**
```python
result = context.step(
    lambda _: payment_service.charge(amount),
    name='process-payment'
)
```

**Configuration Options:**
- `retry_strategy`: Custom retry logic
- `step_semantics`: AT_MOST_ONCE_PER_RETRY or AT_LEAST_ONCE_PER_RETRY
- `serdes`: Custom serialization

### Wait

Pauses execution for a specified duration without consuming compute resources.

**TypeScript:**
```typescript
// Wait 1 hour without charges
await context.wait({ seconds: 3600 });
```

**Python:**
```python
# Wait 1 hour without charges
context.wait(3600)
```

### Callbacks

Enables functions to pause and wait for external systems to provide input.

#### createCallback

Creates a callback and returns both a promise and a callback ID.

**TypeScript:**
```typescript
const [promise, callbackId] = await context.createCallback('approval', {
  timeout: { hours: 24 }
});

await sendApprovalRequest(callbackId, requestData);
const approval = await promise;
```

**Python:**
```python
callback = context.create_callback(
    name='approval',
    config=CallbackConfig(timeout_seconds=86400)
)

context.step(
    lambda _: send_approval_request(callback.callback_id),
    name='send_request'
)

approval = callback.result()
```

#### waitForCallback

Simplifies callback handling by combining creation and submission.

**TypeScript:**
```typescript
const result = await context.waitForCallback(
  'external-api',
  async (callbackId, ctx) => {
    await submitToExternalAPI(callbackId, requestData);
  },
  { timeout: { minutes: 30 } }
);
```

**Python:**
```python
result = context.wait_for_callback(
    lambda callback_id: submit_to_external_api(callback_id, request_data),
    name='external-api',
    config=WaitForCallbackConfig(timeout_seconds=1800)
)
```

**Configuration Options:**
- `timeout_seconds`: Maximum wait time
- `heartbeat_timeout_seconds`: Detect when external systems stop responding
- `serdes`: Custom serialization

### Parallel Execution

Executes multiple operations concurrently with optional concurrency control.

**TypeScript:**
```typescript
const results = await context.parallel([
  async (ctx) => ctx.step('task1', async () => processTask1()),
  async (ctx) => ctx.step('task2', async () => processTask2()),
  async (ctx) => ctx.step('task3', async () => processTask3())
]);
```

**Python:**
```python
results = context.parallel(
    lambda ctx: ctx.step(lambda _: process_task1(), name='task1'),
    lambda ctx: ctx.step(lambda _: process_task2(), name='task2'),
    lambda ctx: ctx.step(lambda _: process_task3(), name='task3')
)
```

**Configuration Options:**
- `max_concurrency`: Limit concurrent executions
- `completion_config`: Define success/failure criteria

### Map

Concurrently executes an operation on each item in an array.

**TypeScript:**
```typescript
const results = await context.map(itemArray, async (ctx, item, index) =>
  ctx.step('task', async () => processItem(item, index))
);
```

**Python:**
```python
results = context.map(
    item_array,
    lambda ctx, item, index: ctx.step(
        lambda _: process_item(item, index),
        name='task'
    )
)
```

**Configuration Options:**
- `max_concurrency`: Limit concurrent executions
- `item_batcher`: Control batching behavior
- `completion_config`: Define success/failure criteria

### Child Contexts

Creates an isolated execution context for grouping operations.

**TypeScript:**
```typescript
const result = await context.runInChildContext(
  'batch-processing',
  async (childCtx) => {
    return await processBatch(childCtx, items);
  }
);
```

**Python:**
```python
result = context.run_in_child_context(
    lambda child_ctx: process_batch(child_ctx, items),
    name='batch-processing'
)
```

**Use Cases:**
- Organize complex workflows
- Implement sub-workflows
- Isolate operations that should retry together
- Enable concurrent execution streams with deterministic ordering

**Important:** Child contexts with results larger than 256 KB are reconstructed during replay by re-executing the context's operations.

### Conditional Waits

Polls for a condition with automatic checkpointing between attempts.

**TypeScript:**
```typescript
const result = await context.waitForCondition(
  async (state, ctx) => {
    const status = await checkJobStatus(state.jobId);
    return { ...state, status };
  },
  {
    initialState: { jobId: 'job-123', status: 'pending' },
    waitStrategy: (state) => 
      state.status === 'completed' 
        ? { shouldContinue: false }
        : { shouldContinue: true, delay: { seconds: 30 } }
  }
);
```

**Python:**
```python
result = context.wait_for_condition(
    lambda state, ctx: check_job_status(state['jobId']),
    config=WaitForConditionConfig(
        initial_state={'jobId': 'job-123', 'status': 'pending'},
        wait_strategy=lambda state, attempt: 
            {'should_continue': False} if state['status'] == 'completed'
            else {'should_continue': True, 'delay': 30}
    )
)
```

**Use Cases:**
- Polling external systems
- Waiting for resources to be ready
- Implementing retry with backoff

### Function Invocation

Invokes another Lambda function and waits for its result.

**TypeScript:**
```typescript
const result = await context.invoke(
  'invoke-processor',
  'arn:aws:lambda:us-east-1:123456789012:function:processor',
  { data: inputData }
);
```

**Python:**
```python
result = context.invoke(
    'arn:aws:lambda:us-east-1:123456789012:function:processor',
    {'data': input_data},
    name='invoke-processor'
)
```

**Note:** Cross-account invocations are not supported.

## Operation Metering

Each durable operation creates checkpoints that incur charges based on usage. Understanding metering helps estimate costs and optimize workflows.

### Basic Operations

| Operation | Checkpoint Timing | Number of Operations | Data Persisted |
|-----------|------------------|---------------------|----------------|
| Execution | Started | 1 | Input payload size |
| Execution | Completed | 0 | Output payload size |
| Step | Retry/Succeeded/Failed | 1 + N retries | Returned payload from each attempt |
| Wait | Started | 1 | N/A |
| WaitForCondition | Each poll attempt | 1 + N polls | Returned payload from each poll |
| Invocation-level Retry | Started | 1 | Error object payload |

### Callback Operations

| Operation | Checkpoint Timing | Number of Operations | Data Persisted |
|-----------|------------------|---------------------|----------------|
| CreateCallback | Started | 1 | N/A |
| Callback completion | Completed | 0 | Callback payload |
| WaitForCallback | Started | 3 + N retries | Submitter step payloads + 2 copies of callback payload |

### Compound Operations

| Operation | Checkpoint Timing | Number of Operations | Data Persisted |
|-----------|------------------|---------------------|----------------|
| Parallel | Started | 1 + N branches | Up to 2 copies of payload from each branch + statuses |
| Map | Started | 1 + N branches | Up to 2 copies of payload from each iteration + statuses |
| Promise helpers | Completed | 1 | Returned payload size |
| RunInChildContext | Succeeded/Failed | 1 | Returned payload (if < 256 KB) |

**Note:** Child context results larger than 256 KB are not stored directly; they're reconstructed during replay.

## Configuration Classes

### StepConfig

```typescript
interface StepConfig {
  retry_strategy?: (attempt: number) => RetryDecision;
  step_semantics?: 'AT_MOST_ONCE_PER_RETRY' | 'AT_LEAST_ONCE_PER_RETRY';
  serdes?: SerDes;
}
```

### InvokeConfig

```typescript
interface InvokeConfig<P, R> {
  timeout_seconds?: number;
  serdes_payload?: SerDes<P>;
  serdes_result?: SerDes<R>;
}
```

### MapConfig

```typescript
interface MapConfig {
  max_concurrency?: number;
  item_batcher?: ItemBatcher;
  completion_config?: CompletionConfig;
  serdes?: SerDes;
}
```

### ParallelConfig

```typescript
interface ParallelConfig {
  max_concurrency?: number;
  completion_config?: CompletionConfig;
  serdes?: SerDes;
}
```

### CompletionConfig

```typescript
interface CompletionConfig {
  min_successful?: number;
  tolerated_failure_count?: number;
  tolerated_failure_percentage?: number;
}

// Static factory methods
CompletionConfig.first_successful()
CompletionConfig.all_completed()
CompletionConfig.all_successful()
```

### CallbackConfig

```typescript
interface CallbackConfig {
  timeout_seconds?: number;
  heartbeat_timeout_seconds?: number;
  serdes?: SerDes;
}
```

### WaitForConditionConfig

```typescript
interface WaitForConditionConfig<T> {
  wait_strategy: (state: T, attempt: number) => WaitDecision;
  initial_state: T;
  serdes?: SerDes;
}
```

## Serialization (SerDes)

The SDK provides a serialization/deserialization interface for custom data handling.

### TypeScript

```typescript
interface SerDes<T> {
  serialize(value: T, context: SerDesContext): string;
  deserialize(data: string, context: SerDesContext): T;
}

interface SerDesContext {
  operation_id: string;
  durable_execution_arn: string;
}
```

### Python

```python
class SerDes(ABC):
    @abstractmethod
    def serialize(self, value: T, context: SerDesContext) -> str:
        pass
    
    @abstractmethod
    def deserialize(self, data: str, context: SerDesContext) -> T:
        pass

@dataclass(frozen=True)
class SerDesContext:
    operation_id: str
    durable_execution_arn: str
```

**Default:** JSON serialization is used by default.

## Best Practices

### Write Deterministic Code

Wrap non-deterministic operations in steps:
- Random number generation and UUIDs
- Current time or timestamps
- External API calls and database queries
- File system operations

**Example:**
```typescript
// Generate transaction ID inside a step
const transactionId = await context.step('generate-transaction-id', async () => {
  return randomUUID();
});
```

**Critical:** Don't use global variables or closures to share state between steps. Pass data through return values.

### Avoid Closure Mutations

Variables captured in closures can lose mutations during replay.

**Wrong:**
```typescript
let total = 0;
for (const item of items) {
  await context.step(async () => {
    total += item.price; // ⚠️ Mutation lost on replay!
    return saveItem(item);
  });
}
```

**Correct:**
```typescript
let total = 0;
for (const item of items) {
  total = await context.step(async () => {
    const newTotal = total + item.price;
    await saveItem(item);
    return newTotal; // Return updated value
  });
}
```

### Design for Idempotency

Operations may execute multiple times due to retries or replay.

**Use idempotency tokens:**
```typescript
const idempotencyToken = await context.step('generate-idempotency-token', async () => {
  return crypto.randomUUID();
});

const charge = await context.step('charge-payment', async () => {
  return paymentService.charge({
    amount: event.amount,
    cardToken: event.cardToken,
    idempotencyKey: idempotencyToken
  });
});
```

**Use at-most-once semantics for critical operations:**
```typescript
await context.step('deduct-inventory', async () => {
  return inventoryService.deduct(event.productId, event.quantity);
}, {
  executionMode: 'AT_MOST_ONCE_PER_RETRY'
});
```

### Manage State Efficiently

Keep state minimal:
- Store IDs and references, not full objects
- Fetch detailed data within steps as needed
- Use S3 or DynamoDB for large data
- Avoid passing large payloads between steps

**Example:**
```typescript
// Store only the order ID, not the full order object
const orderId = event.orderId;

// Fetch data within each step as needed
await context.step('validate-order', async () => {
  const order = await orderService.getOrder(orderId);
  return validateOrder(order);
});
```

## Python SDK Architecture

### Core Components

- **DurableContext:** Entry point for all SDK operations
- **ExecutionState:** Manages checkpoint log and state persistence
- **OrderedCounter:** Thread-safe counter for generating sequential step IDs
- **Logger:** Structured logging with context information

### Concurrency Implementation

- **ConcurrentExecutor:** Abstract base class for map/parallel operations
- **MapExecutor/ParallelExecutor:** Concrete implementations
- **ThreadPoolExecutor:** Manages concurrent task execution
- **ExecutableWithState:** Tracks individual task lifecycle
- **ExecutionCounters:** Tracks success/failure criteria
- **TimerScheduler:** Handles timed suspensions and resumptions

### Threading and Locking

- **OrderedLock:** Ensures FIFO ordering for lock acquisition
- **OrderedCounter:** Thread-safe counter with ordered locking
- Uses Python's threading primitives (Lock, Event)

### Checkpoint Batching

The Python SDK uses a background thread to batch checkpoint operations:

**Configuration:**
```python
@dataclass(frozen=True)
class CheckpointBatcherConfig:
    max_batch_size_bytes: int = 750 * 1024  # 750KB
    max_batch_time_seconds: float = 1.0     # 1 second
    max_batch_operations: int | float = float("inf")
```

**Checkpoint Types:**

| Operation Type | Action | Is Sync? | Rationale |
|---------------|--------|----------|-----------|
| Step (AtMostOncePerRetry) | START | Yes | Prevents duplicate execution |
| Step (AtLeastOncePerRetry) | START | No | Performance optimization |
| Step | SUCCEED/FAIL | Yes | Ensures result persisted |
| Callback | START | Yes | Must wait for callback ID |
| Callback | SUCCEED/FAIL | Yes | Ensures callback result persisted |
| Invoke | START | Yes | Ensures chained invoke recorded |
| Invoke | SUCCEED/FAIL | Yes | Ensures invoke result persisted |
| Context (Child) | START | No | Fire-and-forget for performance |
| Context (Child) | SUCCEED/FAIL | Yes | Ensures child result available |
| Wait | START | No | Observability only |
| Wait | SUCCEED | Yes | Ensures wait completion recorded |
| Wait for Condition | START | No | Observability only |
| Wait for Condition | SUCCEED/FAIL | Yes | Ensures condition result persisted |

## Testing

### JavaScript/TypeScript

The SDK provides a testing package: `@aws/durable-execution-sdk-js-testing`

### Python

Testing utilities are included in the main package. Use `hatch` for development:

```bash
# Install dependencies
hatch env create

# Run tests
hatch run test

# Run specific test
hatch run test tests/test_specific.py
```

## Additional Resources

- **JavaScript/TypeScript SDK:** https://github.com/aws/aws-durable-execution-sdk-js
- **Python SDK:** https://github.com/aws/aws-durable-execution-sdk-python
- **API Reference (JS/TS):** https://github.com/aws/aws-durable-execution-sdk-js/blob/main/docs/api-reference/index.md
- **AWS Lambda Developer Guide:** https://docs.aws.amazon.com/lambda/latest/dg/durable-functions.html
- **Lambda Pricing:** https://aws.amazon.com/lambda/pricing/

## License

Both SDKs are licensed under Apache-2.0.
