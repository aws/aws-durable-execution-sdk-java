# Python SDK Design Decisions for AWS Lambda Durable Functions


IMPORTANT: You have access to the Python SDK in ../../Projects - so if you need to analyze it further please navigate there

**Source:** aws-durable-execution-sdk-python  
**Version:** 1.0  
**Last Analyzed:** December 2, 2025

## Overview

This document captures the key architectural and design decisions from the Python SDK implementation to guide development of SDKs in other languages.

## Core Architecture

### 1. Entry Point: DurableContext

**Design Decision:** Single entry point class that consumers interact with.

**Implementation:**
```python
class DurableContext:
    def __init__(self, state: ExecutionState, lambda_context, parent_id, step_counter, log_info, logger):
        self.state = state
        self.lambda_context = lambda_context
        self._parent_id = parent_id
        self._step_counter = step_counter
        self._log_info = log_info
        self.logger = logger
```

**Key Methods:**
- `step()` - Execute atomic operations with retry
- `invoke()` - Call other Lambda functions
- `map()` - Process arrays concurrently
- `parallel()` - Execute multiple operations concurrently
- `run_in_child_context()` - Create isolated execution contexts
- `wait()` - Pause execution
- `create_callback()` - Create callback for external systems
- `wait_for_callback()` - Wait for external callback
- `wait_for_condition()` - Poll until condition met

**Rationale:** Provides clean API surface and encapsulates all complexity.

### 2. Decorator Pattern for Handler Wrapping

**Design Decision:** Use `@durable_execution` decorator to wrap Lambda handlers.

**Implementation:**
```python
@durable_execution
def handler(event, context: DurableContext):
    result = context.step(lambda _: process_data(event))
    return result
```

**Rationale:** Pythonic approach that's familiar to developers and clearly marks durable functions.

### 3. Synchronous API (No async/await)

**Design Decision:** Python SDK uses synchronous methods, not async/await.

**Implementation:**
```python
# Synchronous - Python SDK
result = context.step(lambda _: fetch_data())

# vs TypeScript (async/await)
# const result = await context.step(async () => fetchData());
```

**Rationale:** 
- Simpler mental model for developers
- Avoids async/await complexity
- Lambda Python runtime is primarily synchronous
- Internal threading handles concurrency

### 4. State Management: ExecutionState Class

**Design Decision:** Separate `ExecutionState` class manages all checkpoint operations.

**Key Responsibilities:**
- Checkpoint log management
- Operation result caching
- Background thread for checkpoint batching
- Interaction with Lambda Durable Execution API

**Implementation Pattern:**
```python
class ExecutionState:
    def __init__(self, durable_execution_arn, checkpoint_token, operations, service_client):
        self._checkpoint_log = {}  # operation_id -> Operation
        self._checkpoint_queue = queue.Queue()
        self._background_thread = threading.Thread(target=self._checkpoint_worker)
        self._service_client = service_client
```

**Rationale:** Separation of concerns - context handles user API, state handles persistence.

### 5. Checkpoint Batching with Background Thread

**Design Decision:** Use background thread to batch checkpoint operations.

**Configuration:**
```python
@dataclass(frozen=True)
class CheckpointBatcherConfig:
    max_batch_size_bytes: int = 750 * 1024  # 750KB
    max_batch_time_seconds: float = 1.0     # 1 second
    max_batch_operations: int | float = float("inf")
```

**Batching Logic:**
1. Operations queued to background thread
2. Thread collects operations until:
   - Batch size exceeds 750KB, OR
   - 1 second elapsed since first operation, OR
   - Max operation count reached
3. Single API call made with batched operations

**Synchronous vs Asynchronous Checkpoints:**
- **Synchronous (default):** Caller blocks until checkpoint persisted
- **Asynchronous (opt-in):** Returns immediately, checkpoint happens in background

**Critical Operations (Synchronous):**
- Step START (AT_MOST_ONCE_PER_RETRY semantics)
- Step SUCCEED/FAIL
- Callback START/SUCCEED/FAIL
- Invoke START/SUCCEED/FAIL
- Child context SUCCEED/FAIL
- Wait SUCCEED
- Wait for condition SUCCEED/FAIL

**Non-Critical Operations (Asynchronous):**
- Step START (AT_LEAST_ONCE_PER_RETRY semantics)
- Child context START
- Wait START
- Wait for condition START

**Rationale:** 
- Reduces API overhead
- Improves throughput
- Synchronous checkpoints ensure correctness
- Asynchronous checkpoints optimize performance

### 6. Thread Safety: OrderedCounter and OrderedLock

**Design Decision:** Custom thread-safe primitives for deterministic operation ordering.

**OrderedCounter:**
```python
class OrderedCounter:
    def __init__(self):
        self._lock = OrderedLock()
        self._counter = 0
    
    def increment(self) -> int:
        with self._lock:
            self._counter += 1
            return self._counter
```

**OrderedLock:**
```python
class OrderedLock:
    def __init__(self):
        self._lock = Lock()
        self._waiters = deque()  # FIFO queue of Events
        self._is_broken = False
```

**Rationale:** 
- Ensures deterministic step ID generation
- FIFO ordering prevents race conditions
- Critical for replay correctness

### 7. Operation Handlers: Functional Approach

**Design Decision:** Each operation type has dedicated handler function.

**Pattern:**
```python
def step_handler(func, state, op_id, config, logger) -> T:
    # Check for existing checkpoint
    checkpoint = state.get_checkpoint_result(op_id)
    if checkpoint and checkpoint.status == OperationStatus.SUCCEEDED:
        return deserialize(checkpoint.result)
    
    # Execute with retry logic
    result = execute_with_retry(func, config.retry_strategy)
    
    # Create checkpoint
    state.create_checkpoint(OperationUpdate(...))
    
    return result
```

**Handler Functions:**
- `step_handler()` - Execute steps with retry
- `invoke_handler()` - Invoke other functions
- `map_handler()` - Process arrays
- `parallel_handler()` - Execute in parallel
- `child_handler()` - Run child contexts
- `wait_handler()` - Handle waits
- `create_callback_handler()` - Create callbacks
- `wait_for_callback_handler()` - Wait for callbacks
- `wait_for_condition_handler()` - Poll conditions

**Rationale:** 
- Separation of concerns
- Easier to test and maintain
- Clear responsibility boundaries

### 8. Concurrency: ConcurrentExecutor Pattern

**Design Decision:** Abstract base class for map/parallel operations.

**Architecture:**
```python
class ConcurrentExecutor(ABC):
    def __init__(self, executables, max_concurrency, completion_config):
        self.executables = executables
        self.max_concurrency = max_concurrency
        self.completion_config = completion_config
        self.counters = ExecutionCounters(...)
        self.executables_with_state = [ExecutableWithState(...) for ...]
    
    def execute(self, state, run_in_child_context) -> BatchResult:
        with ThreadPoolExecutor(max_workers=self.max_concurrency) as executor:
            # Submit tasks
            # Track completion
            # Handle suspension
            # Return results
```

**Key Components:**
- **ExecutableWithState:** Tracks individual task lifecycle
- **ExecutionCounters:** Tracks success/failure criteria
- **TimerScheduler:** Handles timed suspensions
- **CompletionConfig:** Defines success/failure thresholds

**Rationale:** 
- Reusable concurrency logic
- Consistent behavior across map/parallel
- Handles complex completion scenarios

### 9. Configuration System: Dataclasses

**Design Decision:** Use Python dataclasses for all configuration.

**Pattern:**
```python
@dataclass(frozen=True)
class StepConfig:
    retry_strategy: Callable | None = None
    step_semantics: StepSemantics = StepSemantics.AT_LEAST_ONCE_PER_RETRY
    serdes: SerDes | None = None

@dataclass(frozen=True)
class MapConfig:
    max_concurrency: int | None = None
    item_batcher: ItemBatcher | None = None
    completion_config: CompletionConfig | None = None
    serdes: SerDes | None = None
```

**Rationale:** 
- Immutable by default (frozen=True)
- Type hints for IDE support
- Clear structure
- Easy to extend

### 10. Serialization: SerDes Interface

**Design Decision:** Abstract serialization/deserialization interface.

**Interface:**
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

**Default Implementation:**
```python
class JsonSerDes(SerDes):
    def serialize(self, value, context):
        return json.dumps(value)
    
    def deserialize(self, data, context):
        return json.loads(data)
```

**Rationale:** 
- Pluggable serialization
- Context-aware (operation ID, execution ARN)
- Supports custom types

### 11. Error Handling: Exception Hierarchy

**Design Decision:** Structured exception hierarchy for different error types.

**Key Exceptions:**
```python
class DurableExecutionsError(Exception):
    """Base exception for all SDK errors"""

class SuspendExecution(DurableExecutionsError):
    """Raised to suspend execution (wait, callback)"""

class CallbackError(DurableExecutionsError):
    """Callback-related errors"""

class ValidationError(DurableExecutionsError):
    """Input validation errors"""

class BackgroundThreadError(DurableExecutionsError):
    """Errors from background checkpoint thread"""

class CallableRuntimeError(DurableExecutionsError):
    """Errors from user code execution"""
```

**Rationale:** 
- Clear error categorization
- Easier error handling
- Better debugging

### 12. Logging: Structured Logger

**Design Decision:** Structured logging with context information.

**Implementation:**
```python
@dataclass(frozen=True)
class LogInfo:
    operation_id: str | None = None
    attempt: int | None = None
    execution_arn: str | None = None

class Logger:
    def __init__(self, logger: LoggerInterface, info: LogInfo):
        self.logger = logger
        self.info = info
    
    def with_log_info(self, info: LogInfo) -> Logger:
        return Logger(self.logger, info)
```

**Rationale:** 
- Context-aware logging
- Easier debugging
- Consistent log format

### 13. Replay Validation

**Design Decision:** Validate operation sequence during replay.

**Validation Checks:**
- Operation type matches checkpoint
- Operation name matches checkpoint
- Operation order is deterministic

**Rationale:** 
- Catch non-deterministic code early
- Prevent subtle bugs
- Clear error messages

### 14. Child Context Isolation

**Design Decision:** Child contexts have isolated state and step counters.

**Implementation:**
```python
def run_in_child_context(self, func, name, config):
    child_context = DurableContext(
        state=self.state,
        lambda_context=self.lambda_context,
        parent_id=operation_id,  # Link to parent
        step_counter=OrderedCounter(),  # New counter
        log_info=self._log_info,
        logger=self.logger
    )
    return child_handler(func, self.state, op_id, config)
```

**Rationale:** 
- Isolated step numbering
- Prevents conflicts
- Enables concurrent child contexts

### 15. Completion Policies

**Design Decision:** Flexible completion policies for concurrent operations.

**CompletionConfig:**
```python
@dataclass(frozen=True)
class CompletionConfig:
    min_successful: int | None = None
    tolerated_failure_count: int | None = None
    tolerated_failure_percentage: float | None = None
    
    @staticmethod
    def first_successful() -> CompletionConfig:
        return CompletionConfig(min_successful=1)
    
    @staticmethod
    def all_completed() -> CompletionConfig:
        return CompletionConfig()
    
    @staticmethod
    def all_successful() -> CompletionConfig:
        return CompletionConfig(tolerated_failure_count=0)
```

**Rationale:** 
- Flexible failure tolerance
- Common patterns as static methods
- Clear semantics

## Key Design Patterns

### 1. Decorator Pattern
- `@durable_execution` for handler wrapping
- `@durable_step` for step function wrapping

### 2. Strategy Pattern
- Retry strategies
- Wait strategies
- Serialization strategies

### 3. Template Method Pattern
- `ConcurrentExecutor` base class
- Subclasses implement `execute_item()`

### 4. Builder Pattern
- Configuration classes with defaults
- Static factory methods

### 5. Observer Pattern
- Background thread observes checkpoint queue
- Completion events for synchronous operations

## Critical Implementation Details

### 1. Determinism Requirements

**Code outside steps must be deterministic:**
- No random number generation
- No current time/timestamps
- No external API calls
- No file system operations

**Rationale:** Code outside steps re-executes during replay.

### 2. Closure Mutation Pitfall

**Problem:**
```python
# WRONG - mutations lost on replay
total = 0
for item in items:
    context.step(lambda _: total += item.price)  # Lost!
```

**Solution:**
```python
# CORRECT - return updated value
total = 0
for item in items:
    total = context.step(lambda _: total + item.price)
```

**Rationale:** Steps return cached results during replay, but variable updates outside steps aren't replayed.

### 3. Idempotency Tokens

**Pattern:**
```python
# Generate token once in a step
token = context.step(lambda _: str(uuid.uuid4()), name='generate-token')

# Use token for idempotent operations
result = context.step(
    lambda _: payment_service.charge(amount, idempotency_key=token),
    name='charge-payment'
)
```

**Rationale:** Prevents duplicate operations during retries.

### 4. State Size Management

**Best Practice:** Store IDs, not full objects.

```python
# GOOD - store only ID
order_id = event['orderId']
order = context.step(lambda _: fetch_order(order_id))

# BAD - stores full object in checkpoint
order = event['order']  # Large object
result = context.step(lambda _: process_order(order))
```

**Rationale:** Checkpoints have size limits (256 KB for child contexts).

### 5. Step Semantics

**AT_LEAST_ONCE_PER_RETRY (default):**
- Step may execute multiple times
- Checkpoint START is asynchronous
- Better performance

**AT_MOST_ONCE_PER_RETRY:**
- Step executes at most once per retry
- Checkpoint START is synchronous
- Prevents duplicate execution

**Usage:**
```python
# For idempotent operations (default)
result = context.step(lambda _: fetch_data())

# For non-idempotent operations
result = context.step(
    lambda _: deduct_inventory(),
    config=StepConfig(semantics=StepSemantics.AT_MOST_ONCE_PER_RETRY)
)
```

## Testing Considerations

### 1. Unit Testing

**Pattern:** Mock `ExecutionState` and test handlers independently.

```python
def test_step_handler():
    mock_state = Mock(spec=ExecutionState)
    mock_state.get_checkpoint_result.return_value = None
    
    result = step_handler(lambda _: "test", mock_state, op_id, config, logger)
    
    assert result == "test"
    mock_state.create_checkpoint.assert_called_once()
```

### 2. Integration Testing

**Pattern:** Use test runner to simulate Lambda environment.

```python
# SDK provides testing utilities
from aws_durable_execution_sdk_python.testing import LocalTestRunner

runner = LocalTestRunner(handler)
result = runner.run(event)
assert result.status == "SUCCESS"
```

## Migration Path for Other Languages

### 1. Core Components to Implement

**Essential:**
1. DurableContext class
2. ExecutionState class
3. Checkpoint batching with background thread
4. Operation handlers (step, invoke, map, parallel, etc.)
5. Configuration classes
6. SerDes interface
7. Exception hierarchy

**Important:**
8. Thread-safe primitives (OrderedCounter, OrderedLock)
9. ConcurrentExecutor pattern
10. Logging infrastructure
11. Replay validation

### 2. Language-Specific Adaptations

**For Java:**
- Use interfaces instead of protocols
- CompletableFuture for async operations
- ExecutorService for thread pools
- Annotations instead of decorators
- Builder pattern for configuration

**For Go:**
- Interfaces for abstractions
- Goroutines for concurrency
- Channels for coordination
- Struct tags for serialization
- Error values instead of exceptions

**For C#:**
- Async/await pattern
- Task Parallel Library
- Attributes instead of decorators
- LINQ for collection operations
- IDisposable for resource management

### 3. Testing Strategy

1. Port existing test cases
2. Add language-specific tests
3. Integration tests with Lambda
4. Performance benchmarks
5. Concurrency stress tests

## Performance Considerations

### 1. Checkpoint Batching

**Impact:** Reduces API calls by 10-100x depending on operation frequency.

**Tuning:**
- Increase `max_batch_time_seconds` for higher throughput
- Decrease for lower latency
- Adjust `max_batch_size_bytes` based on operation size

### 2. Thread Pool Sizing

**Default:** `max_concurrency` parameter controls thread pool size.

**Tuning:**
- Higher concurrency = more memory
- Lower concurrency = longer execution time
- Consider Lambda memory limits

### 3. Serialization Overhead

**Impact:** JSON serialization can be slow for large objects.

**Optimization:**
- Use custom SerDes for binary formats
- Compress large payloads
- Store large data externally (S3, DynamoDB)

## Security Considerations

### 1. Checkpoint Data

**Encryption:** Checkpoints encrypted at rest by Lambda.

**Access Control:** IAM permissions required:
- `lambda:CheckpointDurableExecutions`
- `lambda:GetDurableExecutionState`

### 2. Callback Security

**Validation:** Validate callback IDs before use.

**Timeouts:** Always set callback timeouts to prevent indefinite waits.

### 3. Invocation Security

**Cross-Account:** Not supported - prevents unauthorized access.

**IAM Roles:** Execution role needs invoke permissions for target functions.

## Conclusion

The Python SDK demonstrates a well-architected approach to durable execution with:
- Clear separation of concerns
- Thread-safe concurrency
- Efficient checkpoint batching
- Flexible configuration
- Comprehensive error handling

These design decisions provide a solid foundation for implementing SDKs in other languages while allowing for language-specific idioms and optimizations.
