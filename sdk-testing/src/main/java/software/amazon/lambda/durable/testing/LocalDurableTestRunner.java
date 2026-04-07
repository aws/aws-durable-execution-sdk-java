// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import com.amazonaws.services.lambda.runtime.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;
import software.amazon.lambda.durable.testing.local.OperationResult;

/**
 * In-memory test runner for durable Lambda functions. Simulates the Lambda re-invocation loop locally without requiring
 * AWS infrastructure, enabling fast unit and integration tests.
 *
 * @param <I> the handler input type
 * @param <O> the handler output type
 */
public class LocalDurableTestRunner<I, O> {
    private static final int MAX_INVOCATIONS = 100;

    private final TypeToken<I> inputType;
    private final TypeToken<O> outputType;
    private final BiFunction<I, DurableContext, O> handler;
    private final LocalMemoryExecutionClient storage;
    private final SerDes serDes;
    private final DurableConfig customerConfig;

    private LocalDurableTestRunner(
            TypeToken<I> inputType,
            TypeToken<O> outputType,
            BiFunction<I, DurableContext, O> handlerFn,
            DurableConfig customerConfig) {
        this.inputType = inputType;
        this.outputType = outputType;
        this.handler = handlerFn;
        this.storage = new LocalMemoryExecutionClient();

        // Create config that uses customer's configuration but overrides the client with in-memory storage
        if (customerConfig != null) {
            // Use customer's config but override the client with our in-memory implementation
            this.customerConfig = DurableConfig.builder()
                    .withDurableExecutionClient(storage)
                    .withSerDes(customerConfig.getSerDes())
                    .withExecutorService(customerConfig.getExecutorService())
                    .withPollingStrategy(customerConfig.getPollingStrategy())
                    .withCheckpointDelay(customerConfig.getCheckpointDelay())
                    .withLoggerConfig(customerConfig.getLoggerConfig())
                    .build();
        } else {
            // Fallback to default config with in-memory client
            this.customerConfig =
                    DurableConfig.builder().withDurableExecutionClient(storage).build();
        }
        this.serDes = this.customerConfig.getSerDes();
    }

    /**
     * Creates a LocalDurableTestRunner with default configuration. Use this method when your handler uses the default
     * DurableConfig.
     *
     * @param inputType The input type class
     * @param handlerFn The handler function
     * @param <I> Input type
     * @param <O> Output type
     * @return LocalDurableTestRunner with default configuration
     */
    public static <I, O> LocalDurableTestRunner<I, O> create(
            Class<I> inputType, BiFunction<I, DurableContext, O> handlerFn) {
        return new LocalDurableTestRunner<>(TypeToken.get(inputType), null, handlerFn, null);
    }

    /**
     * Creates a LocalDurableTestRunner with default configuration. Use this method when your handler uses the default
     * DurableConfig.
     *
     * <p>If your handler has custom configuration (custom SerDes, ExecutorService, etc.), use {@link #create(TypeToken,
     * DurableHandler)} instead to ensure the test runner uses the same configuration as your handler.
     *
     * <p>Optionally, you can also use {@link #create(TypeToken, BiFunction, DurableConfig)} to pass in any
     * DurableConfig directly.
     *
     * @param inputType The input type class
     * @param handlerFn The handler function
     * @param <I> Input type
     * @param <O> Output type
     * @return LocalDurableTestRunner with default configuration
     */
    public static <I, O> LocalDurableTestRunner<I, O> create(
            TypeToken<I> inputType, BiFunction<I, DurableContext, O> handlerFn) {
        return new LocalDurableTestRunner<>(inputType, null, handlerFn, null);
    }

    /**
     * Creates a LocalDurableTestRunner that uses a custom configuration. This allows the test runner to use custom
     * SerDes and other configuration, while overriding the DurableExecutionClient with the in-memory implementation.
     *
     * @param inputType The input type class
     * @param handlerFn The handler function
     * @param config The DurableConfig to use (DurableExecutionClient will be overridden with in-memory implementation)
     * @param <I> Input type
     * @param <O> Output type
     * @return LocalDurableTestRunner configured with the provided settings
     */
    public static <I, O> LocalDurableTestRunner<I, O> create(
            Class<I> inputType, BiFunction<I, DurableContext, O> handlerFn, DurableConfig config) {
        return new LocalDurableTestRunner<>(TypeToken.get(inputType), null, handlerFn, config);
    }
    /**
     * Creates a LocalDurableTestRunner that uses a custom configuration. This allows the test runner to use custom
     * SerDes and other configuration, while overriding the DurableExecutionClient with the in-memory implementation.
     *
     * <p>Use this method when you need to pass a custom DurableConfig directly, for example when testing with a custom
     * SerDes without using a DurableHandler.
     *
     * <p>Example usage:
     *
     * <pre>{@code
     * // Create a custom DurableConfig with custom SerDes
     * var config = DurableConfig.builder()
     *     .withSerDes(new MyCustomSerDes())
     *     .build();
     *
     * // Create test runner with custom configuration
     * var runner = LocalDurableTestRunner.create(
     *     String.class,
     *     (input, context) -> context.step("process", String.class, stepCtx -> "result"),
     *     config
     * );
     *
     * // Run test with custom configuration
     * var result = runner.run("test-input");
     * assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
     * }</pre>
     *
     * @param inputType The input type class
     * @param handlerFn The handler function
     * @param config The DurableConfig to use (DurableExecutionClient will be overridden with in-memory implementation)
     * @param <I> Input type
     * @param <O> Output type
     * @return LocalDurableTestRunner configured with the provided settings
     */
    public static <I, O> LocalDurableTestRunner<I, O> create(
            TypeToken<I> inputType, BiFunction<I, DurableContext, O> handlerFn, DurableConfig config) {
        return new LocalDurableTestRunner<>(inputType, null, handlerFn, config);
    }

    /**
     * Creates a LocalDurableTestRunner from a DurableHandler instance, automatically extracting the configuration. This
     * is a convenient method when you have a handler instance and want to test it with the same configuration it uses
     * in production.
     *
     * @param inputType The input type class
     * @param handler The DurableHandler instance to test
     * @param <I> Input type
     * @param <O> Output type
     * @return LocalDurableTestRunner configured with the handler's settings
     */
    public static <I, O> LocalDurableTestRunner<I, O> create(Class<I> inputType, DurableHandler<I, O> handler) {
        return new LocalDurableTestRunner<>(
                TypeToken.get(inputType), null, handler::handleRequest, handler.getConfiguration());
    }

    /**
     * Overrides the DurableConfig for this test runner. Use this to test with different configurations without creating
     * a new runner instance.
     */
    public LocalDurableTestRunner<I, O> withDurableConfig(DurableConfig config) {
        return new LocalDurableTestRunner<>(inputType, outputType, handler, config);
    }

    /** Overrides the output type for this test runner. */
    public LocalDurableTestRunner<I, O> withOutputType(TypeToken<O> outputType) {
        return new LocalDurableTestRunner<>(inputType, outputType, handler, customerConfig);
    }

    /** Overrides the output type for this test runner. */
    public LocalDurableTestRunner<I, O> withOutputType(Class<O> outputType) {
        return new LocalDurableTestRunner<>(inputType, TypeToken.get(outputType), handler, customerConfig);
    }

    /**
     * Creates a LocalDurableTestRunner from a DurableHandler instance, automatically extracting the configuration. This
     * is a convenient method when you have a handler instance and want to test it with the same configuration it uses
     * in production.
     *
     * <p>This method automatically:
     *
     * <ul>
     *   <li>Uses the handler's configuration (SerDes, ExecutorService, etc.)
     *   <li>Overrides the DurableExecutionClient with the in-memory implementation for testing
     * </ul>
     *
     * <p>Example usage:
     *
     * <pre>{@code
     * // Create handler instance
     * var handler = new MyCustomHandler();
     *
     * // Create test runner from handler (automatically extracts config)
     * var runner = LocalDurableTestRunner.create(String.class, handler);
     *
     * // Run test with the handler's configuration
     * var result = runner.run("test-input");
     * assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
     * }</pre>
     *
     * @param inputType The input type class
     * @param handler The DurableHandler instance to test
     * @param <I> Input type
     * @param <O> Output type
     * @return LocalDurableTestRunner configured with the handler's settings
     */
    public static <I, O> LocalDurableTestRunner<I, O> create(TypeToken<I> inputType, DurableHandler<I, O> handler) {
        return new LocalDurableTestRunner<>(inputType, null, handler::handleRequest, handler.getConfiguration());
    }

    /** Run a single invocation (may return PENDING if waiting/retrying). */
    public TestResult<O> run(I input) {
        var durableInput = createDurableInput(input);

        var output = DurableExecutor.execute(durableInput, mockLambdaContext(), inputType, handler, customerConfig);

        return storage.toTestResult(output, outputType, serDes);
    }

    /**
     * Run until completion (SUCCEEDED or FAILED) or pending manual intervention, simulating Lambda re-invocations.
     * Operations that don't require manual intervention (like WAIT in STARTED or STEP in PENDING) will be automatically
     * advanced.
     *
     * @param input The input to process
     * @return Final test result (SUCCEEDED or FAILED) or PENDING if operations pending manual intervention
     */
    public TestResult<O> runUntilComplete(I input) {
        TestResult<O> result = null;
        for (int i = 0; i < MAX_INVOCATIONS; i++) {
            result = run(input);

            if (result.getStatus() != ExecutionStatus.PENDING || !storage.advanceTime()) {
                // break the loop if
                // - Return SUCCEEDED or FAILED - we're done
                // - Return PENDING and let test manually advance operations if no operations can be auto advanced
                break;
            }
        }
        return result;
    }

    /** Resets a named step operation to STARTED status, simulating a checkpoint failure. */
    public void resetCheckpointToStarted(String stepName) {
        storage.resetCheckpointToStarted(stepName);
    }

    /** Removes a named step operation entirely, simulating loss of a fire-and-forget checkpoint. */
    public void simulateFireAndForgetCheckpointLoss(String stepName) {
        storage.simulateFireAndForgetCheckpointLoss(stepName);
    }

    /** Returns the {@link TestOperation} for the given operation name, or null if not found. */
    public TestOperation getOperation(String name) {
        var op = storage.getOperationByName(name);
        return op != null ? new TestOperation(op, serDes) : null;
    }

    /** Get callback ID for a named callback operation. */
    public String getCallbackId(String operationName) {
        return storage.getCallbackId(operationName);
    }

    /** Complete a callback with success result. */
    public void completeCallback(String callbackId, String result) {
        storage.completeCallback(callbackId, OperationResult.succeeded(result));
    }

    /** Fail a callback with error. */
    public void failCallback(String callbackId, ErrorObject error) {
        storage.completeCallback(callbackId, OperationResult.failed(error));
    }

    /** Timeout a callback. */
    public void timeoutCallback(String callbackId) {
        storage.completeCallback(callbackId, OperationResult.timedout());
    }

    /** Advances all pending operations, simulating time passing for retries and waits. */
    public void advanceTime() {
        storage.advanceTime();
    }

    /** Completes a chained invoke operation with a successful result. */
    public void completeChainedInvoke(String name, String result) {
        storage.completeChainedInvoke(name, OperationResult.succeeded(result));
    }

    /** Marks a chained invoke operation as timed out. */
    public void timeoutChainedInvoke(String name) {
        storage.completeChainedInvoke(name, OperationResult.timedout());
    }

    /** Fails a chained invoke operation with the given error. */
    public void failChainedInvoke(String name, ErrorObject error) {
        storage.completeChainedInvoke(name, OperationResult.failed(error));
    }

    /** Stops a chained invoke operation with the given error. */
    public void stopChainedInvoke(String name, ErrorObject error) {
        storage.completeChainedInvoke(name, OperationResult.stopped(error));
    }

    private DurableExecutionInput createDurableInput(I input) {
        var executionName = UUID.randomUUID().toString();
        var invocationId = UUID.randomUUID().toString();
        var executionArn = String.format(
                "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST/durable-execution/%s/%s",
                executionName, invocationId);
        var inputJson = serDes.serialize(input);
        var executionOp = Operation.builder()
                .id(invocationId)
                .name(executionName)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload(inputJson).build())
                .build();

        // Load previous operations and include them in InitialExecutionState
        var existingOps = storage.getAllOperations();
        var allOps = new ArrayList<>(List.of(executionOp));
        allOps.addAll(existingOps);

        return new DurableExecutionInput(
                executionArn,
                UUID.randomUUID().toString(),
                CheckpointUpdatedExecutionState.builder().operations(allOps).build());
    }

    private Context mockLambdaContext() {
        return null; // Minimal - tests don't need real Lambda context
    }
}
