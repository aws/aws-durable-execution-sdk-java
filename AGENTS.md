# AGENTS.md

AI coding agent instructions for the AWS Lambda Durable Execution Java SDK.

## Project Overview

**Java SDK for AWS Lambda Durable Functions** - enables building resilient, multi-step workflows that can run for up to one year with automatic state management and failure recovery.

### Key Concepts

- **Checkpoint-and-replay**: Operations create checkpoints; on interruption, replay skips completed work
- **Durable operations**: `step()` executes with retry, `wait()` suspends without compute charges
- **Use cases**: Order processing, human approvals, AI agent workflows, distributed transactions

This implements the Java version of AWS's durable execution SDK (official SDKs exist for JavaScript/TypeScript and Python).

## Build & Test Commands

```bash
# Build all modules
mvn clean install

# Run unit tests only
mvn test

# Run specific test class
mvn test -Dtest=DurableContextTest

# Skip tests
mvn install -DskipTests

# Format code (ALWAYS run after making changes)
mvn spotless:apply
```

## Key Directories

```
sdk/                    # Core SDK module
├── src/main/java/com/amazonaws/lambda/durable/
│   ├── DurableHandler.java      # Lambda entry point (extend this)
│   ├── DurableContext.java      # User-facing API (step, wait)
│   ├── DurableExecutor.java     # Execution lifecycle
│   ├── execution/               # Thread coordination, checkpointing
│   ├── operation/               # StepOperation, WaitOperation
│   ├── model/                   # Data structures
│   ├── serde/                   # JSON serialization
│   ├── client/                  # AWS API integration
│   └── exception/               # Domain exceptions

sdk-testing/            # Test utilities (LocalDurableTestRunner, etc.)
examples/               # Customer-facing examples with local and cloud tests
sdk-integration-tests/  # Integration tests for the sdk
```

## Coding Guidelines

### Java Style (MUST follow)

```java
// USE var when type is obvious
var ctx = new DurableContext();
var operations = new HashMap<Integer, Operation>();

// USE static imports for common utilities and factory methods
import static org.junit.jupiter.api.Assertions.*;           // Tests
import static java.util.Collections.emptyList;              // Factory methods
import static software.amazon.lambda.durable.model.Status.*;  // Enums

// ALWAYS use proper imports, NEVER use fully qualified class names in code
// Bad:  var lambda = software.amazon.awssdk.services.lambda.LambdaClient.create();
// Good: import software.amazon.awssdk.services.lambda.LambdaClient;
//       var lambda = LambdaClient.create();

// Bad:  software.amazon.lambda.durable.model.Status.SUCCESS
// Good: import static and use SUCCESS directly

// USE constructor injection
public DurableExecutor(DurableExecutionClient client, SerDes serDes) {
    this.client = client;
    this.serDes = serDes;
}
```

### Architecture Rules

- **No unnecessary interfaces** - Use concrete classes when only one implementation exists
- **Constructor injection** - All dependencies via constructor, no field injection
- **Defensive copies** - Copy mutable collections in constructors
- **Single responsibility** - One class, one job
- **Methods ≤30 lines** - Extract if longer

### Package Naming

Prefer descriptive domain names: `model`, `execution`, `operation`, `serde`, `exception`

### Logging in Examples

Use `context.getLogger()` instead of SLF4J's `LoggerFactory` in example handlers. It includes execution metadata and suppresses duplicate logs during replay.

## Do Not

- Add new dependencies without explicit approval
- Create interfaces for single implementations
- Write tests for POJO getters/setters
- Expose mutable state via getters
- Change public API signatures without instruction
- Swallow exceptions silently
- Use field injection

## Testing Approach

### Test Organization

```
sdk/src/test/                    # Unit tests for SDK internals
├── DurableContextTest           # Test DurableContext behavior
├── DurableExecutorTest          # Test execution lifecycle
├── serde/JacksonSerDesTest      # Test serialization
└── retry/RetryStrategiesTest    # Test retry logic

sdk-integration-tests/src/test/  # Integration tests (SDK + mock AWS)
├── IntegrationTest              # End-to-end with LocalDurableTestRunner
├── RetryIntegrationTest         # Retry behavior across operations
└── StepSemanticsIntegrationTest # Step execution semantics

examples/src/test/               # Customer-facing examples + cloud tests
├── SimpleStepExampleTest        # Local test with LocalDurableTestRunner
├── WaitExampleTest              # Local test for wait operations
└── CloudBasedIntegrationTest    # Cloud tests with CloudDurableTestRunner
```

### Testing Strategy

**Unit Tests (sdk/src/test/)**
- Test individual classes in isolation
- Mock dependencies
- Fast, no external dependencies
- Run on every build

```java
@Test
void stepReturnsResultOnReplay() {
    var context = createTestContext(completedOperations);
    var result = context.step("test", String.class, () -> "new");
    assertEquals("cached", result);  // Returns cached, doesn't re-execute
}
```

**Integration Tests (sdk-integration-tests/)**
- Test SDK components working together
- Use `LocalDurableTestRunner` (in-memory, no AWS)
- Test replay, checkpointing, error handling
- Run on every build

```java
@Test
void testRetryBehavior() {
    var runner = LocalDurableTestRunner.create(Input.class, handler::handleRequest);
    var result = runner.run(new Input("test"));
    assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
}
```

**Example Tests (examples/src/test/)**
- Demonstrate SDK usage patterns
- Local tests use `LocalDurableTestRunner`
- Cloud tests use `CloudDurableTestRunner` (requires deployed Lambda)
- Cloud tests disabled by default (`-Dtest.cloud.enabled=true`)

```java
@Test
@EnabledIf("isCloudTestsEnabled")
void testAgainstRealLambda() {
    var arn = "arn:aws:lambda:us-east-1:123456789012:function:my-fn";
    var runner = CloudDurableTestRunner.create(arn, Input.class, Output.class);
    var result = runner.run(new Input("test"));
    assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
}
```

### Test Guidelines

- Test business logic, replay behavior, edge cases
- Don't test POJO getters/setters
- Use `LocalDurableTestRunner` for fast tests
- Use `CloudDurableTestRunner` only for end-to-end validation
- JUnit 5 with static imports for assertions

## Architecture Essentials

### Checkpoint-and-Replay

1. Operations get sequential IDs
2. Completed operations stored in ExecutionManager
3. On replay: return cached result, skip re-execution
4. New operations: execute, checkpoint, continue

### Key Classes

| Class | Responsibility |
|-------|----------------|
| `DurableHandler<I,O>` | Lambda entry point, extend this |
| `DurableContext` | User API: `step()`, `wait()` |
| `DurableExecutor` | Orchestrates execution lifecycle |
| `ExecutionManager` | Thread coordination, state management |
| `CheckpointBatcher` | Batches checkpoint API calls (750KB limit) |
| `StepOperation` | Executes steps with retry logic |
| `WaitOperation` | Handles wait checkpointing |

## Common Tasks

### Add a New Operation Type

1. Create class in `operation/` implementing `DurableOperation<T>`
2. Add method to `DurableContext` that delegates to new operation
3. Add tests for: first execution, replay, error cases

### Add a Test

```java
@Test
void descriptiveTestName() {
    // Given
    var handler = new MyHandler();
    var runner = LocalDurableTestRunner.create(MyInput.class, handler::handleRequest);
    
    // When
    var result = runner.runUntilComplete(new MyInput("test"));
    
    // Then
    assertEquals(expected, result);
}
```

### Debug Thread Coordination

Check `ExecutionManager` for thread registration and coordination logic if debugging concurrency issues.

## When Unsure

- Ask clarifying questions before making assumptions
- Check existing code for patterns (especially in `operation/` package)
- Prefer minimal changes over large refactors

## After Making Changes

**ALWAYS run `mvn spotless:apply` after making code changes** to ensure consistent formatting across the codebase. This applies code formatting rules automatically.

## Further Reading

### Official AWS SDKs

- **JavaScript/TypeScript**: https://github.com/aws/aws-durable-execution-sdk-js
- **Python**: https://github.com/aws/aws-durable-execution-sdk-python

### AWS Documentation

- [Lambda Durable Functions](https://docs.aws.amazon.com/lambda/latest/dg/durable-functions.html)
- [Durable Execution SDK](https://docs.aws.amazon.com/lambda/latest/dg/durable-execution-sdk.html)
- [Best Practices](https://docs.aws.amazon.com/lambda/latest/dg/durable-best-practices.html)
