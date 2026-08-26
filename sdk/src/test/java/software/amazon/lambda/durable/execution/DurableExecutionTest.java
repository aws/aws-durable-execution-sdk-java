// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static software.amazon.lambda.durable.TypeToken.get;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.operation.BaseDurableOperation;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;

class DurableExecutionTest {

    private static final String EXECUTION_OP_ID = "20dae574-53da-37a1-bfd5-b0e2e6ec715d";
    private static final String OPERATION_ID1 = TestUtils.hashOperationId("1");
    private static final String EXECUTION_NAME = "exec-name";
    private static final String EXECUTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/"
            + EXECUTION_NAME + "/" + EXECUTION_OP_ID;
    private static final Instant EXECUTION_START_TIME = Instant.parse("2026-08-15T00:00:00Z");

    private DurableConfig configWithMockClient() {
        return DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .build();
    }

    @Test
    void testExecuteSuccess() {
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> ctx.step("test", String.class, stepCtx -> "Hello " + userInput),
                configWithMockClient());

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertNotNull(output.result());
        assertTrue(output.result().contains("Hello test-input"));
    }

    @Test
    void testExecutePending() {
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    ctx.step("step1", String.class, stepCtx -> "Done");
                    ctx.wait(null, java.time.Duration.ofSeconds(60));
                    return "Should not reach here";
                },
                configWithMockClient());

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
    }

    @Test
    void testExecuteAsyncSuspendsOnExtensionStageWithoutBlockingHandlerThread() {
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        var startThread = new AtomicReference<Thread>();
        var asyncReturnThread = new AtomicReference<Thread>();
        var plugin = new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                startThread.set(Thread.currentThread());
            }

            @Override
            public void onInvocationAsyncReturn(InvocationInfo info) {
                asyncReturnThread.set(Thread.currentThread());
            }
        };
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withPlugins(plugin)
                .build();
        var output = DurableExecutor.executeAsync(
                input,
                null,
                get(String.class),
                (userInput, context) -> ((ExtensionContext) context)
                        .reserve("wait")
                        .waitAsync(OperationSubType.WAIT.getValue(), Duration.ofMinutes(5))
                        .thenApply(ignored -> "done"),
                config);

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
        assertSame(startThread.get(), asyncReturnThread.get());
    }

    @Test
    void testExecuteAsyncCompletesReplayedExtensionStage() {
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();
        var completedWait = Operation.builder()
                .id(OPERATION_ID1)
                .name("wait")
                .type(OperationType.WAIT)
                .subType(OperationSubType.WAIT.getValue())
                .status(OperationStatus.SUCCEEDED)
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token2",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp, completedWait))
                        .build());

        var output = DurableExecutor.executeAsync(
                input,
                null,
                get(String.class),
                (userInput, context) -> ((ExtensionContext) context)
                        .reserve("wait")
                        .waitAsync(OperationSubType.WAIT.getValue(), Duration.ofMinutes(5))
                        .thenCompose(ignored -> CompletableFuture.completedFuture("done")),
                configWithMockClient());

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertTrue(output.result().contains("done"));
    }

    @Test
    void waiterFirstTerminalCheckpointReturnsSuccessfulOutput() {
        var pendingStep = Operation.builder()
                .id("step")
                .name("step")
                .type(OperationType.STEP)
                .subType(OperationSubType.STEP.getValue())
                .status(OperationStatus.PENDING)
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp(), pendingStep))
                        .build());
        var checkpointAttempted = new CountDownLatch(1);
        var checkpointFuture = new AtomicReference<CompletableFuture<Void>>();

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    var durableContext = (DurableContextImpl) ctx;
                    var manager = durableContext.getExecutionManager();

                    class TestOperation extends BaseDurableOperation {
                        TestOperation() {
                            super(OperationIdentifier.of("step", "step", OperationSubType.STEP), durableContext, null);
                        }

                        @Override
                        protected void start() {}

                        @Override
                        protected void replay(Operation existing) {}

                        Operation awaitCompletion() {
                            return waitForOperationCompletion();
                        }

                        @Override
                        protected void deregisterActiveThread(String threadId) {
                            checkpointFuture.set(CompletableFuture.runAsync(() -> {
                                checkpointAttempted.countDown();
                                try {
                                    manager.onCheckpointComplete(List.of(Operation.builder()
                                            .id("step")
                                            .name("step")
                                            .type(OperationType.STEP)
                                            .subType(OperationSubType.STEP.getValue())
                                            .status(OperationStatus.SUCCEEDED)
                                            .build()));
                                } finally {
                                    manager.finishCheckpointProcessing();
                                }
                            }));
                            try {
                                assertTrue(checkpointAttempted.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(e);
                            }
                            super.deregisterActiveThread(threadId);
                        }
                    }

                    var operation = new TestOperation();
                    operation.execute();
                    assertTrue(manager.tryStartCheckpointProcessing());
                    var completed = operation.awaitCompletion();
                    checkpointFuture.get().join();
                    return completed.statusAsString();
                },
                configWithMockClient());

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertTrue(output.result().contains(OperationStatus.SUCCEEDED.toString()));
    }

    @Test
    void testExecuteFailure() {
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    throw new RuntimeException("Test error");
                },
                configWithMockClient());

        assertEquals(ExecutionStatus.FAILED, output.status());
        assertNotNull(output.error());
        assertEquals("java.lang.RuntimeException", output.error().errorType());
        assertEquals("Test error", output.error().errorMessage());
    }

    @Test
    void testRetryableExceptions() {
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        UnrecoverableDurableExecutionException ex = assertThrows(
                UnrecoverableDurableExecutionException.class,
                () -> DurableExecutor.execute(
                        input,
                        null,
                        get(String.class),
                        (userInput, ctx) -> {
                            throw new UnrecoverableDurableExecutionException(
                                    ErrorObject.builder()
                                            .errorMessage("Test error")
                                            .build(),
                                    true);
                        },
                        configWithMockClient()));

        assertTrue(ex.isRetryable());
    }

    @Test
    void testExecuteReplay() {
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var completedStep = Operation.builder()
                .id(OPERATION_ID1)
                .name("step1")
                .type(OperationType.STEP)
                .subType(OperationSubType.STEP.getValue())
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"First\"").build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token2",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp, completedStep))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> ctx.step("step1", String.class, stepCtx -> "Second"),
                configWithMockClient());

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertTrue(output.result().contains("First"));
    }

    @Test
    void testValidationNoOperations() {
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder().operations(List.of()).build());

        var exception = assertThrows(
                IllegalStateException.class,
                () -> DurableExecutor.execute(
                        input, null, get(String.class), (userInput, ctx) -> "result", configWithMockClient()));

        assertEquals("EXECUTION operation not found", exception.getMessage());
    }

    @Test
    void testValidationWrongFirstOperation() {
        var stepOp = Operation.builder()
                .id(OPERATION_ID1)
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"result\"").build())
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(stepOp))
                        .build());

        var exception = assertThrows(
                IllegalStateException.class,
                () -> DurableExecutor.execute(
                        input, null, get(String.class), (userInput, ctx) -> "result", configWithMockClient()));

        assertEquals("EXECUTION operation not found", exception.getMessage());
    }

    @Test
    void testValidationMissingExecutionDetails() {
        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .build();

        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        var result = DurableExecutor.execute(
                input, null, get(String.class), (userInput, ctx) -> "result", configWithMockClient());

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertEquals(
                "EXECUTION operation missing executionDetails", result.error().errorMessage());
    }

    @Test
    void testExecutorNotShutdownAfterMultipleHandlerInvocations() {
        // Create a config with a shared executor
        var config = configWithMockClient();
        ExecutorService sharedExecutor = config.getExecutorService();

        // Verify executor is not shutdown initially
        assertFalse(sharedExecutor.isShutdown(), "Executor should not be shutdown initially");

        var executionOp = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input-1\"")
                        .build())
                .build();

        var input1 = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp))
                        .build());

        // Execute first handler
        var output1 = DurableExecutor.execute(
                input1,
                null,
                get(String.class),
                (userInput, ctx) -> ctx.step("test1", String.class, stepCtx -> "Result 1: " + userInput),
                config);

        assertEquals(ExecutionStatus.SUCCEEDED, output1.status());
        assertFalse(sharedExecutor.isShutdown(), "Executor should not be shutdown after first execution");

        // Create second input with different execution operation
        var executionOp2 = Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input-2\"")
                        .build())
                .build();

        var input2 = new DurableExecutionInput(
                EXECUTION_ARN,
                "token2",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp2))
                        .build());

        // Execute second handler using the same config (and thus same executor)
        var output2 = DurableExecutor.execute(
                input2,
                null,
                get(String.class),
                (userInput, ctx) -> ctx.step("test2", String.class, stepCtx -> "Result 2: " + userInput),
                config);

        assertEquals(ExecutionStatus.SUCCEEDED, output2.status());
        assertFalse(sharedExecutor.isShutdown(), "Executor should not be shutdown after second execution");

        // Verify both executions completed successfully and used the same executor
        assertTrue(output1.result().contains("Result 1: test-input-1"));
        assertTrue(output2.result().contains("Result 2: test-input-2"));
    }

    private Operation executionOp() {
        return Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(EXECUTION_START_TIME)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();
    }
}
