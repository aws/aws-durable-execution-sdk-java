// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.DurableConfig;
import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableExecutor;
import com.amazonaws.lambda.durable.DurableHandler;
import com.amazonaws.lambda.durable.model.DurableExecutionInput;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.services.lambda.runtime.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;

public class LocalDurableTestRunner<I, O> {
    private static final int MAX_INVOCATIONS = 100;

    private final Class<I> inputType;
    private final BiFunction<I, DurableContext, O> handler;
    private final LocalMemoryExecutionClient storage;
    private final SerDes serDes;
    private final DurableConfig customerConfig;
    private boolean skipTime = true;

    private LocalDurableTestRunner(Class<I> inputType, BiFunction<I, DurableContext, O> handlerFn) {
        this.inputType = inputType;
        this.handler = handlerFn;
        this.storage = new LocalMemoryExecutionClient();
        this.serDes = new JacksonSerDes();
        this.customerConfig = null;
    }

    private LocalDurableTestRunner(
            Class<I> inputType, BiFunction<I, DurableContext, O> handlerFn, DurableConfig customerConfig) {
        this.inputType = inputType;
        this.handler = handlerFn;
        this.storage = new LocalMemoryExecutionClient();
        this.serDes = customerConfig.getSerDes();
        this.customerConfig = customerConfig;
    }

    /**
     * Creates a LocalDurableTestRunner with default configuration. Use this method when your handler uses the default
     * DurableConfig.
     *
     * <p>If your handler has custom configuration (custom SerDes, ExecutorService, etc.), use {@link #create(Class,
     * DurableHandler)} instead to ensure the test runner uses the same configuration as your handler.
     *
     * <p>Optionally, you can also use {@link #create(Class, BiFunction, DurableConfig)} to pass in any DurableConfig
     * directly.
     *
     * @param inputType The input type class
     * @param handlerFn The handler function
     * @param <I> Input type
     * @param <O> Output type
     * @return LocalDurableTestRunner with default configuration
     */
    public static <I, O> LocalDurableTestRunner<I, O> create(
            Class<I> inputType, BiFunction<I, DurableContext, O> handlerFn) {
        return new LocalDurableTestRunner<>(inputType, handlerFn);
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
     *     (input, context) -> context.step("process", String.class, () -> "result"),
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
            Class<I> inputType, BiFunction<I, DurableContext, O> handlerFn, DurableConfig config) {
        return new LocalDurableTestRunner<>(inputType, handlerFn, config);
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
    public static <I, O> LocalDurableTestRunner<I, O> create(Class<I> inputType, DurableHandler<I, O> handler) {
        return new LocalDurableTestRunner<>(inputType, handler::handleRequest, handler.getConfiguration());
    }

    public LocalDurableTestRunner<I, O> withSkipTime(boolean skipTime) {
        this.skipTime = skipTime;
        return this;
    }

    /** Run a single invocation (may return PENDING if waiting/retrying). */
    public TestResult<O> run(I input) {
        var durableInput = createDurableInput(input);

        // Create config that uses customer's configuration but overrides the client with in-memory storage
        DurableConfig config;
        if (customerConfig != null) {
            // Use customer's config but override the client with our in-memory implementation
            config = DurableConfig.builder()
                    .withDurableExecutionClient(storage)
                    .withSerDes(customerConfig.getSerDes())
                    .withExecutorService(customerConfig.getExecutorService())
                    .build();
        } else {
            // Fallback to default config with in-memory client
            config = DurableConfig.builder().withDurableExecutionClient(storage).build();
        }

        var output = DurableExecutor.execute(durableInput, mockLambdaContext(), inputType, handler, config);

        return storage.toTestResult(output);
    }

    /** Run until completion (SUCCEEDED or FAILED), simulating Lambda re-invocations. */
    public TestResult<O> runUntilComplete(I input) {
        TestResult<O> result = null;
        for (int i = 0; i < MAX_INVOCATIONS; i++) {
            result = run(input);

            if (result.getStatus() != ExecutionStatus.PENDING) {
                return result; // SUCCEEDED or FAILED - we're done
            }

            if (skipTime) {
                storage.advanceReadyOperations(); // Auto-advance and continue loop
            } else {
                return result; // Return PENDING - let test manually advance time
            }
        }
        return result;
    }

    public void resetCheckpointToStarted(String stepName) {
        storage.resetCheckpointToStarted(stepName);
    }

    public void simulateFireAndForgetCheckpointLoss(String stepName) {
        storage.simulateFireAndForgetCheckpointLoss(stepName);
    }

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
        storage.completeCallback(callbackId, result);
    }

    /** Fail a callback with error. */
    public void failCallback(String callbackId, ErrorObject error) {
        storage.failCallback(callbackId, error);
    }

    /** Timeout a callback. */
    public void timeoutCallback(String callbackId) {
        storage.timeoutCallback(callbackId);
    }

    // Manual time advancement for skipTime=false scenarios
    public void advanceTime() {
        storage.advanceReadyOperations();
    }

    private DurableExecutionInput createDurableInput(I input) {
        var inputJson = serDes.serialize(input);
        var executionOp = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload(inputJson).build())
                .build();

        // Load previous operations and include them in InitialExecutionState
        var existingOps = storage.getExecutionState("arn:aws:lambda:us-east-1:123456789012:function:test", null)
                .operations();
        var allOps = new ArrayList<>(List.of(executionOp));
        allOps.addAll(existingOps);

        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "test-token",
                new DurableExecutionInput.InitialExecutionState(allOps, null));
    }

    private Context mockLambdaContext() {
        return null; // Minimal - tests don't need real Lambda context
    }
}
