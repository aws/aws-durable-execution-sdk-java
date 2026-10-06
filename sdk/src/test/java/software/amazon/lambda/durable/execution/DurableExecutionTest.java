// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static software.amazon.lambda.durable.TypeToken.get;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
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
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.operation.BaseDurableOperation;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
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

    /**
     * A checkpoint response with no checkpointToken for a non-terminal update must abandon the execution (PENDING)
     * rather than continue issuing further checkpoint or getExecutionState calls with the now-spent token.
     */
    @Test
    void checkpointTokenRevoked_nonTerminalUpdate_suspendsExecutionAsPending() {
        var client = mock(DurableExecutionClient.class);
        var revokeNextStep = new AtomicBoolean(false);
        when(client.checkpoint(any(), any(), any())).thenAnswer(invocation -> {
            List<OperationUpdate> updates = invocation.getArgument(2);
            var responseOperations = new ArrayList<Operation>();
            for (var update : updates) {
                var opBuilder = Operation.builder()
                        .id(update.id())
                        .name(update.name())
                        .subType(update.subType())
                        .type(update.type());
                if (update.action() == OperationAction.START) {
                    opBuilder.status(OperationStatus.STARTED);
                } else if (update.action() == OperationAction.SUCCEED) {
                    opBuilder.status(OperationStatus.SUCCEEDED);
                    opBuilder.stepDetails(
                            StepDetails.builder().result(update.payload()).build());
                }
                responseOperations.add(opBuilder.build());
            }
            var responseBuilder = CheckpointDurableExecutionResponse.builder()
                    .newExecutionState(CheckpointUpdatedExecutionState.builder()
                            .operations(responseOperations)
                            .build());
            if (revokeNextStep.get()) {
                // No checkpointToken: the service has revoked this execution's checkpoint token.
                return responseBuilder.build();
            }
            revokeNextStep.set(true);
            return responseBuilder.checkpointToken("token-after-step1").build();
        });

        var config = DurableConfig.builder().withDurableExecutionClient(client).build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    ctx.step("step1", String.class, stepCtx -> "first");
                    // step2's checkpoint round-trip returns a token-less response: this must
                    // surface as a suspension (PENDING), never as an exception escaping to the
                    // caller and never as a further API call with the now-spent token.
                    ctx.step("step2", String.class, stepCtx -> "second");
                    return "unreachable";
                },
                config);

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
        verify(client, times(0)).getExecutionState(any(), any(), any());
    }

    /**
     * A checkpoint response with no checkpointToken during a wait operation must abandon the execution (PENDING) the
     * same way a revoked token during a step does.
     */
    @Test
    void checkpointTokenRevoked_duringWait_suspendsExecutionAsPending() {
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any())).thenAnswer(invocation -> {
            List<OperationUpdate> updates = invocation.getArgument(2);
            var responseBuilder = CheckpointDurableExecutionResponse.builder();
            var isWaitBatch = updates.stream().anyMatch(update -> update.type() == OperationType.WAIT);
            if (isWaitBatch) {
                // No checkpointToken: the service has revoked this execution's checkpoint token.
                return responseBuilder.build();
            }
            return responseBuilder.checkpointToken("token-x").build();
        });

        var config = DurableConfig.builder().withDurableExecutionClient(client).build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    // The wait's checkpoint round-trip returns a token-less response: this must surface
                    // as a suspension (PENDING), never as an exception escaping to the caller.
                    ctx.wait(null, Duration.ofSeconds(60));
                    return "unreachable";
                },
                config);

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
        assertNull(output.error());
    }

    /**
     * A checkpoint response with no checkpointToken during a callback operation must abandon the execution (PENDING)
     * the same way a revoked token during a step does.
     */
    @Test
    void checkpointTokenRevoked_duringCallback_suspendsExecutionAsPending() {
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any())).thenAnswer(invocation -> {
            List<OperationUpdate> updates = invocation.getArgument(2);
            var responseBuilder = CheckpointDurableExecutionResponse.builder();
            var isCallbackBatch = updates.stream().anyMatch(update -> update.type() == OperationType.CALLBACK);
            if (isCallbackBatch) {
                // No checkpointToken: the service has revoked this execution's checkpoint token.
                return responseBuilder.build();
            }
            return responseBuilder.checkpointToken("token-x").build();
        });

        var config = DurableConfig.builder().withDurableExecutionClient(client).build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    // The callback's checkpoint round-trip returns a token-less response: this must surface
                    // as a suspension (PENDING), never as an exception escaping to the caller.
                    ctx.createCallback("callback1", String.class).get();
                    return "unreachable";
                },
                config);

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
        assertNull(output.error());
    }

    /**
     * A checkpoint response with no checkpointToken for one branch of a parallel operation must abandon the execution
     * (PENDING), pinning the current behavior where a branch's revoked-token failure is never surfaced as a FAILED
     * branch result.
     */
    @Test
    void checkpointTokenRevoked_duringParallelBranch_suspendsExecutionAsPending() {
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any())).thenAnswer(invocation -> {
            List<OperationUpdate> updates = invocation.getArgument(2);
            var responseBuilder = CheckpointDurableExecutionResponse.builder();
            var isRevokedBranchStep = updates.stream().anyMatch(update -> "branch-step".equals(update.name()));
            if (isRevokedBranchStep) {
                // No checkpointToken: the service has revoked this execution's checkpoint token.
                return responseBuilder.build();
            }
            return responseBuilder.checkpointToken("token-x").build();
        });

        var config = DurableConfig.builder().withDurableExecutionClient(client).build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    // The branch's step checkpoint round-trip returns a token-less response: this must surface
                    // as a suspension (PENDING), never as a FAILED branch result.
                    try (var parallel = ctx.parallel("parallel1")) {
                        parallel.branch(
                                "branch1",
                                String.class,
                                branchCtx -> branchCtx.step("branch-step", String.class, stepCtx -> "branch-result"));
                        return parallel.get().toString();
                    }
                },
                config);

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
        assertNull(output.error());
    }

    /**
     * A checkpoint response with no checkpointToken for one branch of a map operation must abandon the execution
     * (PENDING), the same way a revoked token during a parallel branch does.
     */
    @Test
    void checkpointTokenRevoked_duringMapBranch_suspendsExecutionAsPending() {
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any())).thenAnswer(invocation -> {
            List<OperationUpdate> updates = invocation.getArgument(2);
            var responseBuilder = CheckpointDurableExecutionResponse.builder();
            var isRevokedItemStep = updates.stream().anyMatch(update -> "map-item-step".equals(update.name()));
            if (isRevokedItemStep) {
                // No checkpointToken: the service has revoked this execution's checkpoint token.
                return responseBuilder.build();
            }
            return responseBuilder.checkpointToken("token-x").build();
        });

        var config = DurableConfig.builder().withDurableExecutionClient(client).build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    // The map item's step checkpoint round-trip returns a token-less response: this must surface
                    // as a suspension (PENDING), never as a FAILED item result.
                    var result = ctx.map(
                            "map1",
                            List.of("item1"),
                            String.class,
                            (item, index, itemCtx) ->
                                    itemCtx.step("map-item-step", String.class, stepCtx -> "item-result"));
                    return result.toString();
                },
                config);

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
        assertNull(output.error());
    }

    /**
     * A checkpoint response with no checkpointToken is not a revocation when the batch contains the terminal EXECUTION
     * update: the execution has already finished, so the accepted result stands and the run reports SUCCEEDED.
     */
    @Test
    void checkpointTokenRevoked_terminalExecutionUpdate_stillReportsSucceeded() {
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any())).thenAnswer(invocation -> {
            List<OperationUpdate> updates = invocation.getArgument(2);
            var responseOperations = new ArrayList<Operation>();
            for (var update : updates) {
                var opBuilder = Operation.builder()
                        .id(update.id())
                        .name(update.name())
                        .subType(update.subType())
                        .type(update.type());
                if (update.action() == OperationAction.START) {
                    opBuilder.status(OperationStatus.STARTED);
                } else if (update.action() == OperationAction.SUCCEED) {
                    opBuilder.status(OperationStatus.SUCCEEDED);
                    if (update.type() == OperationType.STEP) {
                        opBuilder.stepDetails(
                                StepDetails.builder().result(update.payload()).build());
                    }
                }
                responseOperations.add(opBuilder.build());
            }
            var responseBuilder = CheckpointDurableExecutionResponse.builder()
                    .newExecutionState(CheckpointUpdatedExecutionState.builder()
                            .operations(responseOperations)
                            .build());
            var isTerminalExecutionBatch =
                    updates.stream().anyMatch(update -> update.type() == OperationType.EXECUTION);
            if (isTerminalExecutionBatch) {
                // No checkpointToken, but this batch includes the terminal EXECUTION update:
                // treat the accepted result as final rather than as a revoked-token suspension.
                return responseBuilder.build();
            }
            return responseBuilder.checkpointToken("token-x").build();
        });

        var config = DurableConfig.builder().withDurableExecutionClient(client).build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> ctx.step("step1", String.class, stepCtx -> "done"),
                config);

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertNotNull(output.result());
    }

    /**
     * A checkpoint the handler never awaited is still in flight -- and comes back token-less -- when the handler itself
     * throws. The invocation must report PENDING (not FAILED), the response itself carries no error, but the handler's
     * real error must still reach plugins.
     */
    @Test
    void checkpointTokenRevoked_whileHandlerErrorInFlight_reportsPendingButStillNotifiesPluginOfRealError()
            throws Exception {
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any())).thenAnswer(invocation -> {
            List<OperationUpdate> updates = invocation.getArgument(2);
            var isBackgroundBatch = updates.stream().anyMatch(update -> "bg-op".equals(update.id()));
            var responseBuilder = CheckpointDurableExecutionResponse.builder();
            if (isBackgroundBatch) {
                // Token-less response: the service revoked this execution's checkpoint token.
                return responseBuilder.build();
            }
            return responseBuilder.checkpointToken("token-x").build();
        });

        var invocationEndInfo = new AtomicReference<InvocationEndInfo>();
        DurableExecutionPlugin plugin = new DurableExecutionPlugin() {
            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                invocationEndInfo.set(info);
            }
        };

        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withPlugins(plugin)
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    var executionManager = ((DurableContextImpl) ctx).getExecutionManager();
                    // Fire-and-forget: the handler never awaits this checkpoint.
                    executionManager.sendOperationUpdate(OperationUpdate.builder()
                            .id("bg-op")
                            .type(OperationType.STEP)
                            .action(OperationAction.START)
                            .build());

                    var deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!executionManager.isCheckpointTokenRevoked() && System.nanoTime() < deadlineNanos) {
                        Thread.onSpinWait();
                    }
                    assertTrue(
                            executionManager.isCheckpointTokenRevoked(),
                            "Timed out waiting for the background checkpoint to revoke the token");

                    throw new RuntimeException("handler failed while a checkpoint was still in flight");
                },
                config);

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
        assertNull(output.error());

        assertNotNull(invocationEndInfo.get());
        assertEquals(InvocationStatus.PENDING, invocationEndInfo.get().invocationStatus());
        assertNotNull(invocationEndInfo.get().executionError());
        assertEquals(
                "handler failed while a checkpoint was still in flight",
                invocationEndInfo.get().executionError().getMessage());
    }

    /**
     * An oversized result whose checkpoint batch is abandoned because the token was already revoked. The invocation
     * must report PENDING and return promptly rather than block until the Lambda timeout.
     */
    @Test
    void checkpointTokenRevoked_beforeOversizedResultCheckpoint_returnsPendingPromptly() throws Exception {
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any())).thenAnswer(invocation -> {
            List<OperationUpdate> updates = invocation.getArgument(2);
            var isBackgroundBatch = updates.stream().anyMatch(update -> "bg-op".equals(update.id()));
            var responseBuilder = CheckpointDurableExecutionResponse.builder();
            if (isBackgroundBatch) {
                return responseBuilder.build();
            }
            return responseBuilder.checkpointToken("token-x").build();
        });

        var config = DurableConfig.builder().withDurableExecutionClient(client).build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var startNanos = System.nanoTime();
        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    var executionManager = ((DurableContextImpl) ctx).getExecutionManager();
                    executionManager.sendOperationUpdate(OperationUpdate.builder()
                            .id("bg-op")
                            .type(OperationType.STEP)
                            .action(OperationAction.START)
                            .build());

                    var deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!executionManager.isCheckpointTokenRevoked() && System.nanoTime() < deadlineNanos) {
                        Thread.onSpinWait();
                    }
                    assertTrue(
                            executionManager.isCheckpointTokenRevoked(),
                            "Timed out waiting for the background checkpoint to revoke the token");

                    // Oversized result: handleLargePayload's own checkpoint call must be abandoned by the
                    // top-of-method latch guard before it ever reaches the client.
                    return "x".repeat(7 * 1024 * 1024);
                },
                config);
        var elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
        assertTrue(elapsedMillis < 5_000, "Expected a prompt PENDING response, took " + elapsedMillis + "ms");
        // Only the background batch reached the client; the oversized-result checkpoint never did.
        verify(client, times(1)).checkpoint(any(), any(), any());
    }

    /**
     * A background checkpoint the handler never awaited comes back token-less while the handler itself returns a small,
     * non-oversized result. The invocation must report PENDING (not SUCCEEDED), the result must not be serialized or
     * checkpointed, and plugins must see the same PENDING outcome as the other revocation paths.
     */
    @Test
    void checkpointTokenRevoked_whileSmallResultInFlight_reportsPendingInsteadOfSucceeded() throws Exception {
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any())).thenAnswer(invocation -> {
            List<OperationUpdate> updates = invocation.getArgument(2);
            var isBackgroundBatch = updates.stream().anyMatch(update -> "bg-op".equals(update.id()));
            var responseBuilder = CheckpointDurableExecutionResponse.builder();
            if (isBackgroundBatch) {
                // Token-less response: the service revoked this execution's checkpoint token.
                return responseBuilder.build();
            }
            return responseBuilder.checkpointToken("token-x").build();
        });

        var invocationEndInfo = new AtomicReference<InvocationEndInfo>();
        DurableExecutionPlugin plugin = new DurableExecutionPlugin() {
            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                invocationEndInfo.set(info);
            }
        };

        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withPlugins(plugin)
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    var executionManager = ((DurableContextImpl) ctx).getExecutionManager();
                    // Fire-and-forget: the handler never awaits this checkpoint.
                    executionManager.sendOperationUpdate(OperationUpdate.builder()
                            .id("bg-op")
                            .type(OperationType.STEP)
                            .action(OperationAction.START)
                            .build());

                    var deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!executionManager.isCheckpointTokenRevoked() && System.nanoTime() < deadlineNanos) {
                        Thread.onSpinWait();
                    }
                    assertTrue(
                            executionManager.isCheckpointTokenRevoked(),
                            "Timed out waiting for the background checkpoint to revoke the token");

                    // Small, non-oversized result: this must not be serialized or checkpointed.
                    return "small-result";
                },
                config);

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
        // Only the background batch reached the client; the small result's own terminal update never did.
        verify(client, times(1)).checkpoint(any(), any(), any());

        assertNotNull(invocationEndInfo.get());
        assertEquals(InvocationStatus.PENDING, invocationEndInfo.get().invocationStatus());
        assertNull(invocationEndInfo.get().executionError());
    }

    /**
     * A background checkpoint can still be queued when the handler returns. Terminal output must not be chosen until
     * that queued checkpoint is flushed, because the flush can discover that the service revoked the checkpoint token.
     */
    @Test
    void checkpointTokenRevoked_afterHandlerReturnsDuringDrain_reportsPendingInsteadOfSucceeded() throws Exception {
        var handlerReturned = new CountDownLatch(1);
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any())).thenAnswer(invocation -> {
            List<OperationUpdate> updates = invocation.getArgument(2);
            var isBackgroundBatch = updates.stream().anyMatch(update -> "bg-op".equals(update.id()));
            if (isBackgroundBatch) {
                assertTrue(handlerReturned.await(5, TimeUnit.SECONDS), "Checkpoint flushed before handler returned");
                return CheckpointDurableExecutionResponse.builder().build();
            }
            return CheckpointDurableExecutionResponse.builder()
                    .checkpointToken("token-x")
                    .build();
        });

        var invocationEndInfo = new AtomicReference<InvocationEndInfo>();
        DurableExecutionPlugin plugin = new DurableExecutionPlugin() {
            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                invocationEndInfo.set(info);
            }
        };

        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withCheckpointDelay(Duration.ofSeconds(60))
                .withPlugins(plugin)
                .build();
        var input = new DurableExecutionInput(
                EXECUTION_ARN,
                "token1",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(executionOp()))
                        .build());

        var output = DurableExecutor.execute(
                input,
                null,
                get(String.class),
                (userInput, ctx) -> {
                    var executionManager = ((DurableContextImpl) ctx).getExecutionManager();
                    executionManager.sendOperationUpdate(OperationUpdate.builder()
                            .id("bg-op")
                            .type(OperationType.STEP)
                            .action(OperationAction.START)
                            .build());

                    handlerReturned.countDown();
                    return "small-result";
                },
                config);

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
        verify(client, times(1)).checkpoint(any(), any(), any());

        assertNotNull(invocationEndInfo.get());
        assertEquals(InvocationStatus.PENDING, invocationEndInfo.get().invocationStatus());
        assertNull(invocationEndInfo.get().executionError());
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
