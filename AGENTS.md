# AGENTS.md

AI coding agent instructions for the AWS Lambda Durable Execution Java SDK.

## Project Overview

**AWS Lambda Durable Execution SDK for Java** - enables building resilient, multi-step Lambda workflows that can run for up to one year with automatic state management, checkpoint-and-replay, and failure recovery.

### Key Concepts

- **Checkpoint-and-replay**: Durable operations create checkpoints; on reinvocation, completed operations replay from cached state instead of re-running user code.
- **Durable operations**: `step()` executes code with retry, `wait()` suspends without compute charges, callbacks resume on external events, and invoke/child-context/concurrency APIs coordinate larger workflows.
- **Concurrency**: `map()` and `parallel()` run work in child contexts, using isolated checkpoint logs and configurable completion behavior.
- **Replay-aware logging and plugins**: `DurableLogger` suppresses duplicate replay logs for handlers; plugin hooks expose invocation, operation, and user-function lifecycle events.
- **Use cases**: Order processing, human approvals, AI agent workflows, distributed transactions, and long-running orchestrations.

This repository is the Java implementation of the AWS Lambda durable execution SDK. Related SDKs exist for JavaScript/TypeScript and Python.

## Build & Test Commands

```bash
# Build all modules and run tests
mvn clean install

# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=DurableContextTest

# Run tests for a module
mvn -pl sdk test
mvn -pl otel-plugin test

# Skip tests
mvn install -DskipTests

# Format Java code (ALWAYS run after making Java changes)
mvn spotless:apply
```

Cloud example tests are disabled by default. Enable them only when the required Lambda functions and AWS credentials are configured:

```bash
cd examples
mvn test -Dtest="CloudBasedIntegrationTest,CloudBasedOtelIntegrationTest" -Dtest.cloud.enabled=true
```

The project targets Java 17. The examples module has a Java 21 virtual-thread example that is automatically excluded on JDKs below 21.

## Key Directories

```text
sdk/                    # Core SDK module
├── src/main/java/software/amazon/lambda/durable/
│   ├── DurableHandler.java      # Lambda entry point (extend this)
│   ├── DurableContext.java      # User-facing API interface
│   ├── StepContext.java         # Context available inside step functions
│   ├── DurableConfig.java       # SDK configuration
│   ├── DurableFuture.java       # Future API that can suspend execution
│   ├── context/                 # DurableContext/StepContext implementations
│   ├── execution/               # DurableExecutor, ExecutionManager, checkpointing
│   ├── operation/               # Step, wait, invoke, callback, child, map, parallel operations
│   ├── config/                  # Operation and SDK configuration types
│   ├── model/                   # Result/status data structures
│   ├── retry/                   # Retry, polling, wait strategies
│   ├── serde/                   # JSON serialization
│   ├── client/                  # AWS Lambda Durable Functions client integration
│   ├── logging/                 # DurableLogger and logger configuration
│   ├── plugin/                  # Preview lifecycle plugin API
│   └── exception/               # Domain exceptions

sdk-testing/            # LocalDurableTestRunner, CloudDurableTestRunner, test helpers
sdk-integration-tests/  # Integration tests using sdk-testing
otel-plugin/            # Preview OpenTelemetry plugin implementation
examples/               # Customer-facing examples with local and cloud tests
coverage-report/        # Aggregated JaCoCo coverage report module
docs/                   # User docs, design notes, ADRs, migration guide
```

## Coding Guidelines

### Java Style (MUST follow)

```java
// USE var when type is obvious
var config = StepConfig.builder().build();
var operations = new HashMap<String, Operation>();

// USE static imports for common utilities, assertions, and enum constants
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.amazon.lambda.durable.model.ExecutionStatus.SUCCEEDED;

// ALWAYS use proper imports, NEVER use fully qualified class names in code
// Bad:  var lambda = software.amazon.awssdk.services.lambda.LambdaClient.create();
// Good: import software.amazon.awssdk.services.lambda.LambdaClient;
//       var lambda = LambdaClient.create();

// Prefer StepContext-based step lambdas; Supplier overloads are deprecated
var result = context.step("process", String.class, stepCtx -> "done");

// USE constructor injection
public DurableExecutor(DurableExecutionClient client, SerDes serDes) {
    this.client = client;
    this.serDes = serDes;
}
```

### Architecture Rules

- **No unnecessary interfaces** - Use concrete classes when only one implementation exists. Keep existing public interfaces such as `DurableContext`, `DurableFuture`, `SerDes`, `RetryStrategy`, and plugin interfaces.
- **Constructor injection** - All dependencies via constructors or builders, no field injection.
- **Defensive copies** - Copy mutable collections in constructors and builders.
- **Single responsibility** - One class, one job.
- **Methods <=30 lines** - Extract helpers if longer.
- **Preserve public API compatibility** - Do not change public method signatures or remove deprecated overloads unless explicitly instructed.
- **TypeToken for generics** - Use `TypeToken<T>` overloads when result types are generic.

### Package Naming

The package root is `software.amazon.lambda.durable`. Prefer descriptive domain packages: `model`, `execution`, `operation`, `serde`, `exception`, `config`, `retry`, `context`, `plugin`, and `logging`.

### Logging in Examples

Use `context.getLogger()` instead of SLF4J's `LoggerFactory` in example handlers. It includes execution metadata and suppresses duplicate handler logs during replay.

`StepContext` logs are attempt-based and are not replay-suppressed. `isReplaying()` is available on `DurableContext`, not `StepContext` or `BaseContext`.

## Do Not

- Add new dependencies without explicit approval.
- Create interfaces for single implementations.
- Write tests for POJO getters/setters.
- Expose mutable state via getters.
- Change public API signatures without instruction.
- Swallow exceptions silently.
- Use field injection.
- Use `Thread.sleep()` or blocking timers to model durable waits; use `context.wait()` or polling/wait strategies.
- Use deprecated Supplier-based `step()` overloads in new examples or tests.

## Testing Approach

### Test Organization

```text
sdk/src/test/                    # Unit tests for SDK internals
├── DurableContextTest           # Public API/default behavior
├── context/DurableContextImplTest
├── execution/ExecutionManagerTest
├── operation/StepOperationTest
├── serde/JacksonSerDesTest
└── retry/RetryStrategiesTest

sdk-integration-tests/src/test/  # SDK components working together via LocalDurableTestRunner
├── IntegrationTest
├── RetryIntegrationTest
├── InvokeIntegrationTest
├── CallbackIntegrationTest
├── MapIntegrationTest
├── ParallelIntegrationTest
├── WaitForConditionIntegrationTest
└── OtelPluginIntegrationTest

otel-plugin/src/test/            # OpenTelemetry plugin unit tests

examples/src/test/               # Customer-facing examples + cloud tests
├── step/SimpleStepExampleTest
├── wait/WaitExampleTest
├── callback/CallbackExampleTest
├── invoke/InvokeExampleTest
├── map/SimpleMapExampleTest
├── parallel/ParallelExampleTest
├── otel/OtelXRayExamplesTest
├── CloudBasedIntegrationTest
└── CloudBasedOtelIntegrationTest
```

### Testing Strategy

**Unit Tests (`sdk/src/test/`)**

- Test individual classes in isolation.
- Mock dependencies.
- Fast, no external dependencies.
- Cover replay behavior, error paths, validation, and serialization edge cases.

```java
@Test
void stepReturnsResultOnReplay() {
    var context = createTestContext(completedOperations);
    var result = context.step("test", String.class, stepCtx -> "new");
    assertEquals("cached", result);
}
```

**Integration Tests (`sdk-integration-tests/`)**

- Test SDK components working together.
- Use `LocalDurableTestRunner` (in-memory, no AWS).
- Test replay, checkpointing, suspension/resume, retry, callbacks, child contexts, and concurrency.

```java
@Test
void testRetryBehavior() {
    var runner = LocalDurableTestRunner.create(Input.class, handler::handleRequest);
    var result = runner.runUntilComplete(new Input("test"));
    assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
}
```

Use `runner.run(input)` when you need to inspect an intermediate `PENDING` state. Use `runner.runUntilComplete(input)` when the test should auto-advance waits/retries that do not need manual intervention.

**Example Tests (`examples/src/test/`)**

- Demonstrate SDK usage patterns.
- Local tests use `LocalDurableTestRunner`.
- Cloud tests use `CloudDurableTestRunner` and are disabled by default (`-Dtest.cloud.enabled=true`).

```java
@Test
@EnabledIf("isCloudTestsEnabled")
void testAgainstRealLambda() {
    var arn = "arn:aws:lambda:us-east-1:123456789012:function:my-fn:live";
    var runner = CloudDurableTestRunner.create(arn, Input.class, Output.class);
    var result = runner.run(new Input("test"));
    assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
}
```

### Test Guidelines

- Test business logic, replay behavior, suspension/resume behavior, and edge cases.
- Do not test simple POJO getters/setters.
- Use `LocalDurableTestRunner` for fast tests.
- Use `CloudDurableTestRunner` only for end-to-end validation.
- Prefer `runUntilComplete()` unless the test needs to assert an intermediate `PENDING` state.
- JUnit uses static imports for assertions.

## Architecture Essentials

### Checkpoint-and-Replay

1. Operations get stable, globally unique string IDs.
2. Child context operation IDs are prefixed by parent operation IDs (for example, `1-1`).
3. Completed operations are stored in `ExecutionManager`.
4. On replay, cached operation state is returned and user code is skipped where appropriate.
5. New operations checkpoint through `CheckpointManager`.
6. `CheckpointManager` batches and polls durable API requests through `ApiRequestDelayedBatcher` (750 KB and 200 update limits).

### Key Classes

| Class | Responsibility |
|-------|----------------|
| `DurableHandler<I,O>` | Lambda entry point, extend this |
| `DurableContext` | User API: `step`, `wait`, `invoke`, callbacks, child contexts, `map`, `parallel`, `waitForCondition`, `withRetry` |
| `StepContext` | Context available inside step functions |
| `DurableFuture<T>` | Future abstraction whose `get()` may suspend execution |
| `DurableExecutor` | Orchestrates execution lifecycle |
| `ExecutionManager` | Thread coordination, replay state, operation storage |
| `CheckpointManager` | Batches checkpoint API calls and polls for operation updates |
| `ApiRequestDelayedBatcher` | Shared delayed batching primitive for checkpoint/poll requests |
| `BaseDurableOperation` | Shared operation lifecycle base class |
| `SerializableDurableOperation<T>` | Base for operations with serialized results/exceptions |
| `StepOperation` | Executes steps with retry logic and step semantics |
| `WaitOperation` | Handles durable waits |
| `CallbackOperation` | Creates external callback wait points |
| `InvokeOperation` | Invokes another Lambda function and waits for its result |
| `ChildContextOperation` | Runs isolated child durable contexts |
| `WaitForConditionOperation` | Polls a condition function with configurable backoff |
| `MapOperation` | Applies a function across items concurrently via child contexts |
| `ParallelOperation` | Runs named child-context branches concurrently |
| `ConcurrencyOperation` | Shared base for map/parallel concurrency limiting and completion evaluation |
| `DurableExecutionPlugin` | Preview lifecycle hook interface |
| `OtelPlugin` | OpenTelemetry plugin implementation in `otel-plugin` |

### Serialization

The default SerDes is `JacksonSerDes`. Custom `SerDes` implementations must be able to deserialize SDK-managed values immediately after serialization. The SDK validates this by default so first execution matches replay behavior; `DurableConfig.withDeserializeAfterSerialization(false)` is an escape hatch, not the default recommendation.

## Common Tasks

### Add a New Operation Type

1. Create a class in `operation/` extending `BaseDurableOperation` or `SerializableDurableOperation<T>`.
2. Add configuration/result/exception types only when the operation needs them.
3. Add sync and async methods to `DurableContext`.
4. Implement delegation in `context/DurableContextImpl`.
5. Add unit tests for first execution, replay, validation, and error cases.
6. Add integration tests using `LocalDurableTestRunner`.
7. Add or update examples and docs if the operation is public.

### Add or Change Configuration

1. Update the relevant `config/` builder.
2. Validate invalid values at build time.
3. Preserve immutability and defensive copies.
4. Add unit tests for defaults, custom values, and validation failures.

### Add an Example

1. Put the handler under the matching `examples/src/main/java/.../examples/<category>/` package.
2. Use `context.getLogger()` for example logging.
3. Add a local test using `LocalDurableTestRunner`.
4. Add cloud coverage only when the scenario needs deployed Lambda behavior.

### Debug Thread Coordination

Check `ExecutionManager` for active thread registration/deregistration, replay transitions, and operation lookup. Check `CheckpointManager` and `ApiRequestDelayedBatcher` for checkpoint batching, polling, and suspension/resume issues.

## When Unsure

- Ask clarifying questions before making risky assumptions.
- Check existing code for patterns, especially in `operation/`, `context/`, `config/`, and nearby tests.
- Prefer minimal changes over large refactors.
- Review `docs/design.md` and the relevant `docs/core/*.md` page for operation semantics.

## After Making Changes

Run `mvn spotless:apply` after Java changes. Then run the narrowest relevant test first, and broaden to `mvn test` or `mvn clean install` when touching shared behavior, public APIs, serialization, concurrency, or execution lifecycle code.

## Further Reading

### Repository Docs

- [Internal Design](docs/design.md)
- [Testing](docs/advanced/testing.md)
- [Configuration](docs/advanced/configuration.md)
- [Error Handling](docs/advanced/error-handling.md)
- [Logging](docs/advanced/logging.md)
- [Migration from 1.x to 2.x](docs/migration-1.x-to-2.x.md)

### Official AWS SDKs

- **JavaScript/TypeScript**: https://github.com/aws/aws-durable-execution-sdk-js
- **Python**: https://github.com/aws/aws-durable-execution-sdk-python

### AWS Documentation

- [Lambda Durable Functions](https://docs.aws.amazon.com/lambda/latest/dg/durable-functions.html)
- [Durable Execution SDK](https://docs.aws.amazon.com/lambda/latest/dg/durable-execution-sdk.html)
- [Best Practices](https://docs.aws.amazon.com/lambda/latest/dg/durable-best-practices.html)
