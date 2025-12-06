# TypeScript SDK Design Decisions for AWS Lambda Durable Functions

**Source:** aws-durable-execution-sdk-js  
**Version:** 1.0  
**Last Analyzed:** December 2, 2025

## Overview

This document captures the key architectural and design decisions from the TypeScript/JavaScript SDK implementation to guide development of SDKs in other languages.

## Core Architecture

### 1. Entry Point: withDurableExecution Wrapper

**Design Decision:** Higher-order function that wraps Lambda handlers.

**Implementation:**
```typescript
export const handler = withDurableExecution(
  async (event: any, context: DurableContext) => {
    const result = await context.step(async () => processData(event));
    return result;
  }
);
```

**Rationale:** 
- Idiomatic JavaScript/TypeScript pattern
- Transparent to Lambda runtime
- Handles initialization and cleanup

### 2. Async/Await Pattern

**Design Decision:** TypeScript SDK uses async/await throughout.

**Implementation:**
```typescript
// Async/await - TypeScript SDK
const result = await context.step(async () => fetchData());

// vs Python (synchronous)
// result = context.step(lambda _: fetch_data())
```

**Rationale:**
- Native JavaScript async model
- Better integration with Node.js ecosystem
- Familiar to JavaScript developers
- Enables true concurrent execution

### 3. DurableContext Interface

**Design Decision:** Context object provides all durable operations.

**Key Methods:**
```typescript
interface DurableContext {
  step<T>(name: string, func: StepFunc<T>, config?: StepConfig): Promise<T>;
  invoke<P, R>(name: string, arn: string, payload: P, config?: InvokeConfig): Promise<R>;
  map<T, R>(items: T[], func: MapFunc<T, R>, config?: MapConfig): Promise<BatchResult<R>>;
  parallel<T>(funcs: ParallelFunc<T>[], config?: ParallelConfig): Promise<BatchResult<T>>;
  runInChildContext<T>(name: string, func: ChildFunc<T>, config?: ChildConfig): Promise<T>;
  wait(duration: Duration): Promise<void>;
  createCallback<T>(name: string, config?: CallbackConfig): Promise<[Promise<T>, string]>;
  waitForCallback<T>(name: string, submitter: CallbackSubmitter, config?: CallbackConfig): Promise<T>;
  waitForCondition<T>(name: string, check: ConditionCheck<T>, config: WaitForConditionConfig<T>): Promise<T>;
  promise: DurablePromise;  // Promise utilities
  logger: DurableLogger;
  lambdaContext: Context;
}
```

**Rationale:** Single interface for all operations with strong typing.

### 4. Execution Context Pattern

**Design Decision:** Separate `ExecutionContext` manages execution state.

**Structure:**
```typescript
interface ExecutionContext {
  durableExecutionArn: string;
  durableExecutionClient: DurableExecutionClient;
  terminationManager: TerminationManager;
  activeOperationsTracker: ActiveOperationsTracker;
  _stepData: Map<string, any>;
  pendingCompletions: Map<string, CompletionHandler>;
}
```

**Rationale:**
- Separation of concerns
- Shared state across operations
- Lifecycle management

### 5. Checkpoint Manager

**Design Decision:** Dedicated class for checkpoint operations.

**Key Responsibilities:**
```typescript
class CheckpointManager {
  constructor(
    durableExecutionArn: string,
    stepData: Map<string, any>,
    client: DurableExecutionClient,
    terminationManager: TerminationManager,
    activeOperationsTracker: ActiveOperationsTracker,
    checkpointToken: string,
    stepDataEmitter: EventEmitter,
    logger: DurableLogger,
    pendingCompletions: Map<string, CompletionHandler>
  );
  
  async checkpoint(operation: OperationUpdate): Promise<void>;
  setTerminating(): void;
}
```

**Checkpoint Batching:**
- Uses EventEmitter for operation notifications
- Batches operations before API calls
- Handles termination gracefully

**Rationale:**
- Encapsulates checkpoint complexity
- Manages API interactions
- Handles batching and optimization

### 6. Handler Architecture

**Design Decision:** Separate handler modules for each operation type.

**Directory Structure:**
```
handlers/
├── step-handler/
├── invoke-handler/
├── map-handler/
├── parallel-handler/
├── callback-handler/
├── wait-handler/
├── wait-for-callback-handler/
├── wait-for-condition-handler/
├── run-in-child-context-handler/
├── concurrent-execution-handler/
└── promise-handler/
```

**Pattern:**
```typescript
export async function stepHandler<T>(
  context: DurableContext,
  name: string,
  func: StepFunc<T>,
  config: StepConfig
): Promise<T> {
  // Check for existing checkpoint
  const checkpoint = getCheckpoint(name);
  if (checkpoint) return deserialize(checkpoint.result);
  
  // Execute with retry
  const result = await executeWithRetry(func, config.retryStrategy);
  
  // Create checkpoint
  await createCheckpoint(name, result);
  
  return result;
}
```

**Rationale:**
- Modular design
- Easy to test
- Clear responsibilities

### 7. Concurrent Execution Handler

**Design Decision:** Unified handler for map/parallel operations.

**Implementation:**
```typescript
async function concurrentExecutionHandler<T>(
  items: ConcurrentExecutionItem[],
  config: ConcurrencyConfig,
  context: DurableContext
): Promise<BatchResult<T>> {
  const results: BatchItem<T>[] = [];
  const executing = new Set<Promise<void>>();
  
  for (const item of items) {
    // Respect max concurrency
    if (executing.size >= config.maxConcurrency) {
      await Promise.race(executing);
    }
    
    // Execute in child context
    const promise = executeInChildContext(item, context)
      .then(result => results.push({ index: item.index, result, status: 'SUCCEEDED' }))
      .catch(error => results.push({ index: item.index, error, status: 'FAILED' }))
      .finally(() => executing.delete(promise));
    
    executing.add(promise);
  }
  
  await Promise.all(executing);
  
  return new BatchResult(results, config.completionConfig);
}
```

**Rationale:**
- Reusable concurrency logic
- Promise-based coordination
- Handles completion policies

### 8. Type System

**Design Decision:** Comprehensive TypeScript types for all operations.

**Key Types:**
```typescript
// Generic step function
type StepFunc<T> = (context: StepContext) => Promise<T> | T;

// Map function with item and index
type MapFunc<T, R> = (context: DurableContext, item: T, index: number) => Promise<R>;

// Parallel function
type ParallelFunc<T> = (context: DurableContext) => Promise<T>;

// Child context function
type ChildFunc<T> = (childContext: DurableContext) => Promise<T>;

// Callback submitter
type CallbackSubmitter = (callbackId: string, context: StepContext) => Promise<void>;

// Condition check
type ConditionCheck<T> = (state: T, context: WaitForConditionCheckContext) => Promise<T>;
```

**Rationale:**
- Type safety
- IDE autocomplete
- Compile-time error checking

### 9. Configuration Objects

**Design Decision:** Interface-based configuration with optional properties.

**Pattern:**
```typescript
interface StepConfig {
  retryStrategy?: RetryStrategyFunc;
  semantics?: StepSemantics;
  serdes?: SerDes;
}

interface MapConfig {
  maxConcurrency?: number;
  itemNamer?: (item: any, index: number) => string;
  completionConfig?: CompletionConfig;
  serdes?: SerDes;
  itemSerdes?: SerDes;
}

interface CompletionConfig {
  minSuccessful?: number;
  toleratedFailureCount?: number;
  toleratedFailurePercentage?: number;
}
```

**Rationale:**
- Optional properties with sensible defaults
- Type-safe configuration
- Extensible

### 10. Serialization System

**Design Decision:** SerDes interface with context.

**Interface:**
```typescript
interface SerDes<T = any> {
  serialize(value: T, context: SerDesContext): string;
  deserialize(data: string, context: SerDesContext): T;
}

interface SerDesContext {
  operationId: string;
  durableExecutionArn: string;
}
```

**Default Implementation:**
```typescript
class DefaultSerDes implements SerDes {
  serialize(value: any, context: SerDesContext): string {
    return JSON.stringify(value);
  }
  
  deserialize(data: string, context: SerDesContext): any {
    return JSON.parse(data);
  }
}
```

**Advanced Features:**
```typescript
// Class serialization with date handling
const serdes = createClassSerDesWithDates(MyClass);

// Custom serialization
const customSerdes: SerDes<MyType> = {
  serialize: (value, ctx) => customEncode(value),
  deserialize: (data, ctx) => customDecode(data)
};
```

**Rationale:**
- Pluggable serialization
- Context-aware
- Supports complex types

### 11. Error Handling

**Design Decision:** Structured error classes with type information.

**Error Hierarchy:**
```typescript
class DurableOperationError extends Error {
  constructor(
    message: string,
    public errorType: string,
    public errorData?: any,
    public stackTrace?: string,
    public cause?: Error
  );
  
  toErrorObject(): ErrorObject;
  static fromErrorObject(obj: ErrorObject): DurableOperationError;
}

class StepError extends DurableOperationError {
  errorType = 'StepError';
}

class CallbackError extends DurableOperationError {
  errorType = 'CallbackError';
}

class InvokeError extends DurableOperationError {
  errorType = 'InvokeError';
}

class ChildContextError extends DurableOperationError {
  errorType = 'ChildContextError';
}

class WaitForConditionError extends DurableOperationError {
  errorType = 'WaitForConditionError';
}
```

**Rationale:**
- Type-safe error handling
- Serializable errors
- Clear error categorization

### 12. Termination Manager

**Design Decision:** Dedicated manager for graceful termination.

**Purpose:**
- Detect approaching Lambda timeout
- Trigger checkpoint before termination
- Prevent data loss

**Implementation:**
```typescript
class TerminationManager {
  private checkpointTerminatingCallback?: () => void;
  private terminationReason?: TerminationReason;
  
  setCheckpointTerminatingCallback(callback: () => void): void;
  setTerminating(reason: TerminationReason): void;
  isTerminating(): boolean;
  getTerminationReason(): TerminationReason | undefined;
}

enum TerminationReason {
  TIMEOUT = 'TIMEOUT',
  MANUAL = 'MANUAL',
  ERROR = 'ERROR'
}
```

**Rationale:**
- Prevents checkpoint loss
- Graceful shutdown
- Better reliability

### 13. Active Operations Tracker

**Design Decision:** Track in-flight operations for cleanup.

**Purpose:**
- Track all active operations
- Cancel operations on termination
- Prevent orphaned operations

**Implementation:**
```typescript
class ActiveOperationsTracker {
  private operations = new Map<string, Operation>();
  
  add(operationId: string, operation: Operation): void;
  remove(operationId: string): void;
  getAll(): Operation[];
  clear(): void;
}
```

**Rationale:**
- Resource cleanup
- Prevents leaks
- Better observability

### 14. DurablePromise Utilities

**Design Decision:** Promise utilities for durable operations.

**Methods:**
```typescript
interface DurablePromise {
  all<T>(promises: Promise<T>[]): Promise<T[]>;
  allSettled<T>(promises: Promise<T>[]): Promise<PromiseSettledResult<T>[]>;
  race<T>(promises: Promise<T>[]): Promise<T>;
  any<T>(promises: Promise<T>[]): Promise<T>;
}
```

**Implementation:**
- Wraps standard Promise methods
- Creates checkpoints for results
- Enables composition

**Rationale:**
- Familiar API
- Composable operations
- Checkpoint integration

### 15. Logging System

**Design Decision:** Structured logging with durable context.

**Interface:**
```typescript
interface DurableLogger {
  debug(message: string, ...args: any[]): void;
  info(message: string, ...args: any[]): void;
  warn(message: string, ...args: any[]): void;
  error(message: string, ...args: any[]): void;
  log(level: string, message: string, ...args: any[]): void;
  configureDurableLoggingContext(context: DurableLoggingContext): void;
}

interface DurableLoggingContext {
  getDurableLogData(): DurableLogData;
}

interface DurableLogData {
  executionArn: string;
  operationId?: string;
  requestId: string;
  attempt?: number;
  tenantId?: string;
}
```

**Rationale:**
- Context-aware logging
- Structured data
- Better debugging

## Key Design Patterns

### 1. Higher-Order Function Pattern
- `withDurableExecution()` wraps handlers
- Transparent initialization

### 2. Promise Pattern
- Async/await throughout
- Native JavaScript async model

### 3. Builder Pattern
- Configuration objects with defaults
- Fluent interfaces

### 4. Strategy Pattern
- Retry strategies
- Wait strategies
- Serialization strategies

### 5. Observer Pattern
- EventEmitter for checkpoints
- Termination callbacks

### 6. Factory Pattern
- `createDurableContext()`
- `createCheckpointManager()`

## Critical Implementation Details

### 1. Replay Validation

**Validation:**
```typescript
function validateReplay(
  operationId: string,
  operationType: OperationType,
  checkpoint: Operation
): void {
  if (checkpoint.operationType !== operationType) {
    throw new NonDeterministicError(
      `Operation type mismatch: expected ${operationType}, got ${checkpoint.operationType}`
    );
  }
}
```

**Rationale:** Catch non-deterministic code early.

### 2. Step ID Generation

**Pattern:**
```typescript
class StepIdGenerator {
  private counter = 0;
  
  next(): string {
    return `step-${++this.counter}`;
  }
}
```

**Rationale:** Deterministic, sequential IDs.

### 3. Child Context Isolation

**Implementation:**
```typescript
function createChildContext(
  parent: DurableContext,
  operationId: string
): DurableContext {
  return {
    ...parent,
    _stepIdGenerator: new StepIdGenerator(),  // New generator
    _parentId: operationId,  // Link to parent
  };
}
```

**Rationale:** Isolated step numbering, prevents conflicts.

### 4. Completion Policies

**Implementation:**
```typescript
class BatchResult<T> {
  constructor(
    public all: BatchItem<T>[],
    public completionReason: CompletionReason
  ) {}
  
  succeeded(): BatchItem<T>[] {
    return this.all.filter(item => item.status === 'SUCCEEDED');
  }
  
  failed(): BatchItem<T>[] {
    return this.all.filter(item => item.status === 'FAILED');
  }
  
  getResults(): T[] {
    return this.succeeded().map(item => item.result);
  }
  
  throwIfError(): void {
    const failed = this.failed();
    if (failed.length > 0) {
      throw new BatchExecutionError(failed);
    }
  }
}
```

**Rationale:** Flexible result handling.

### 5. Duration Helper

**Implementation:**
```typescript
interface Duration {
  seconds?: number;
  minutes?: number;
  hours?: number;
  days?: number;
}

function durationToSeconds(duration: Duration): number {
  return (
    (duration.seconds ?? 0) +
    (duration.minutes ?? 0) * 60 +
    (duration.hours ?? 0) * 3600 +
    (duration.days ?? 0) * 86400
  );
}
```

**Rationale:** User-friendly time specification.

## Testing Infrastructure

### 1. Local Test Runner

**Purpose:** Test durable functions locally without Lambda.

**Usage:**
```typescript
import { LocalDurableTestRunner } from '@aws/durable-execution-sdk-js-testing';

const runner = new LocalDurableTestRunner({
  handlerFunction: handler
});

const result = await runner.run({ userId: '123' });
expect(result.getStatus()).toBe('SUCCEEDED');
```

### 2. Cloud Test Runner

**Purpose:** Test against real Lambda environment.

**Usage:**
```typescript
import { CloudDurableTestRunner } from '@aws/durable-execution-sdk-js-testing';

const runner = new CloudDurableTestRunner({
  functionName: 'my-durable-function',
  client: lambdaClient
});

const result = await runner.run({ userId: '123' });
```

### 3. Fake Clock

**Purpose:** Control time in tests.

**Usage:**
```typescript
const runner = new LocalDurableTestRunner({
  handlerFunction: handler,
  skipTime: true  // Enable fake clock
});

await runner.run(event);
runner.fakeClock.tick(3600);  // Advance 1 hour
```

## Migration Path for Java

### 1. Core Components

**Essential:**
1. `DurableContext` interface
2. `ExecutionContext` class
3. `CheckpointManager` class
4. Handler classes for each operation
5. Configuration classes
6. `SerDes` interface
7. Exception hierarchy

### 2. Java-Specific Adaptations

**Use CompletableFuture:**
```java
public interface DurableContext {
    <T> CompletableFuture<T> step(String name, Supplier<T> func, StepConfig config);
    <P, R> CompletableFuture<R> invoke(String name, String arn, P payload, InvokeConfig config);
    // ... other methods
}
```

**Use Annotations:**
```java
@DurableExecution
public class MyHandler implements RequestHandler<Event, Result> {
    @Override
    public Result handleRequest(Event event, DurableContext context) {
        return context.step("process", () -> processData(event)).join();
    }
}
```

**Use Builder Pattern:**
```java
StepConfig config = StepConfig.builder()
    .retryStrategy(RetryStrategy.exponentialBackoff())
    .semantics(StepSemantics.AT_MOST_ONCE_PER_RETRY)
    .build();
```

**Use ExecutorService:**
```java
class ConcurrentExecutionHandler {
    private final ExecutorService executor;
    
    public <T> BatchResult<T> execute(
        List<ConcurrentExecutionItem> items,
        ConcurrencyConfig config
    ) {
        List<CompletableFuture<T>> futures = items.stream()
            .map(item -> CompletableFuture.supplyAsync(
                () -> executeItem(item),
                executor
            ))
            .collect(Collectors.toList());
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> createBatchResult(futures))
            .join();
    }
}
```

### 3. Key Differences from TypeScript

**Async Model:**
- TypeScript: async/await with Promises
- Java: CompletableFuture with callbacks or .join()

**Type System:**
- TypeScript: Structural typing
- Java: Nominal typing with interfaces

**Configuration:**
- TypeScript: Object literals
- Java: Builder pattern

**Error Handling:**
- TypeScript: try/catch with Error objects
- Java: try/catch with Exception hierarchy

**Serialization:**
- TypeScript: JSON.stringify/parse
- Java: Jackson or Gson

## Performance Considerations

### 1. Promise Overhead

**Impact:** Promise creation has overhead.

**Optimization:**
- Reuse promises where possible
- Avoid unnecessary async/await
- Use Promise.all for parallel operations

### 2. Checkpoint Batching

**Impact:** Reduces API calls significantly.

**Tuning:**
- Batch size limits
- Time-based flushing
- Operation count limits

### 3. Memory Management

**Impact:** Large checkpoint logs consume memory.

**Optimization:**
- Limit state size
- Use external storage for large data
- Clean up completed operations

## Conclusion

The TypeScript SDK demonstrates:
- Idiomatic JavaScript/TypeScript patterns
- Strong type safety
- Comprehensive async/await support
- Modular architecture
- Extensive testing infrastructure

These design decisions provide a solid foundation for Java implementation while allowing for Java-specific idioms and optimizations.
