// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static software.amazon.lambda.durable.TypeToken.get;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.operation.BaseDurableOperation;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationStatus;

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
                    ctx.wait(null, Duration.ofSeconds(60));
                    return "Should not reach here";
                },
                configWithMockClient());

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
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
        var waiterReady = new CountDownLatch(1);
        var checkpointFuture = new AtomicReference<CompletableFuture<Void>>();

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    var durableContext = (DurableContextImpl) ctx;
                    var manager = durableContext.getExecutionManager();

                    class TestOperation extends BaseDurableOperation {
                        private final CompletableFuture<BaseDurableOperation> completionFuture =
                                new CompletableFuture<>() {
                                    @Override
                                    public CompletableFuture<Void> thenRun(Runnable action) {
                                        waiterReady.countDown();
                                        return super.thenRun(action);
                                    }
                                };

                        TestOperation() {
                            super(OperationIdentifier.of("step", "step", OperationSubType.STEP), durableContext, null);
                        }

                        @Override
                        public CompletableFuture<BaseDurableOperation> getCompletionFuture() {
                            return completionFuture;
                        }

                        @Override
                        protected void start() {}

                        @Override
                        protected void replay(Operation existing) {}

                        Operation awaitCompletion() {
                            return waitForOperationCompletion();
                        }
                    }

                    var succeededStep = Operation.builder()
                            .id("step")
                            .name("step")
                            .type(OperationType.STEP)
                            .subType(OperationSubType.STEP.getValue())
                            .status(OperationStatus.SUCCEEDED)
                            .build();
                    var operation = new TestOperation();
                    operation.execute();

                    assertTrue(manager.tryStartCheckpointProcessing());

                    checkpointFuture.set(CompletableFuture.runAsync(() -> {
                        try {
                            assertTrue(waiterReady.await(5, TimeUnit.SECONDS));
                            manager.onCheckpointComplete(List.of(succeededStep));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        } finally {
                            manager.finishCheckpointProcessing();
                        }
                    }));
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

    private enum OperationKind {
        STEP,
        WAIT,
        CALLBACK,
        MAP,
        PARALLEL
    }

    @ParameterizedTest
    @EnumSource(OperationKind.class)
    @Timeout(10)
    void checkpointTokenRevoked_duringOperation_suspendsExecutionAsPending(OperationKind kind) {
        var client = mock(DurableExecutionClient.class);
        var revoked = new AtomicBoolean();
        when(client.checkpoint(any(), any(), any())).thenAnswer(invocation -> {
            List<OperationUpdate> updates = invocation.getArgument(2);
            var revoke = updates.stream().anyMatch(update -> "revoke".equals(update.name()));
            if (revoke) {
                revoked.set(true);
            }
            return CheckpointDurableExecutionResponse.builder()
                    .checkpointToken(revoke ? null : "next-token")
                    .build();
        });

        var config = DurableConfig.builder().withDurableExecutionClient(client).build();
        var output = execute(config, (userInput, ctx) -> {
            executeRevokingOperation(kind, ctx);
            return "unreachable";
        });

        assertTrue(revoked.get(), "The selected operation must receive a token-less checkpoint response");
        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
        assertNull(output.error());
        verify(client, never()).getExecutionState(any(), any(), any());
    }

    private void executeRevokingOperation(OperationKind kind, DurableContext ctx) {
        switch (kind) {
            case STEP -> ctx.step("revoke", String.class, stepCtx -> "done");
            case WAIT -> ctx.wait("revoke", Duration.ofSeconds(60));
            case CALLBACK -> ctx.createCallback("revoke", String.class).get();
            case MAP ->
                ctx.map(
                        "map",
                        List.of("item"),
                        String.class,
                        (item, index, child) -> child.step("revoke", String.class, stepCtx -> "done"));
            case PARALLEL -> {
                try (var parallel = ctx.parallel("parallel")) {
                    parallel.branch(
                            "branch", String.class, child -> child.step("revoke", String.class, stepCtx -> "done"));
                    parallel.get();
                }
            }
        }
    }

    @ParameterizedTest(name = "largeResult={0}")
    @ValueSource(booleans = {false, true})
    @Timeout(10)
    void checkpointTokenRevoked_beforeHandlerReturns_reportsPending(boolean largeResult) throws Exception {
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any()))
                .thenReturn(CheckpointDurableExecutionResponse.builder().build());
        var plugin = mock(DurableExecutionPlugin.class);
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withPlugins(plugin)
                .build();

        var handlerReadyToReturn = new CountDownLatch(1);
        var output = execute(config, handlerAfterRevocation(largeResult, handlerReadyToReturn));

        assertTrue(handlerReadyToReturn.await(5, TimeUnit.SECONDS), "Handler must observe suspension before returning");
        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
        assertNull(output.error());
        verify(client, times(1)).checkpoint(any(), any(), any());
        verify(plugin, times(1))
                .onInvocationEnd(argThat(info -> info.invocationStatus() == InvocationStatus.PENDING
                        && info.executionError() == null
                        && info.executionResult() == null));
    }

    private BiFunction<String, DurableContext, String> handlerAfterRevocation(
            boolean largeResult, CountDownLatch handlerReadyToReturn) {
        return (userInput, ctx) -> {
            var manager = ((DurableContextImpl) ctx).getExecutionManager();
            var checkpoint = manager.sendOperationUpdate(OperationUpdate.builder()
                    .id("bg-op")
                    .type(OperationType.STEP)
                    .action(OperationAction.START)
                    .build());
            assertSuspended(checkpoint);
            assertTrue(manager.isCheckpointTokenRevoked());
            assertTrue(manager.isExecutionCompletedExceptionally());
            var result = largeResult ? "x".repeat(7 * 1024 * 1024) : "small-result";
            handlerReadyToReturn.countDown();
            return result;
        };
    }

    private DurableExecutionOutput execute(DurableConfig config, BiFunction<String, DurableContext, String> handler) {
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(executionOp())
                        .build());
        return DurableExecutor.execute(input, null, get(String.class), handler, config);
    }

    private void assertSuspended(CompletableFuture<?> future) {
        var error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(SuspendExecutionException.class, error.getCause());
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
