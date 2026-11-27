// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.StepSemantics;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

/** AT_LEAST_ONCE_ASYNC returns the step result before the service answers the SUCCEED checkpoint. */
@Timeout(20)
class StepAtLeastOnceAsyncIntegrationTest {
    private static final String EXECUTION_OP_ID = "01234567-0123-0123-0123-012345678901";
    private static final String EXECUTION_ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/" + EXECUTION_OP_ID;
    private static final StepConfig ASYNC = StepConfig.builder()
            .semanticsPerRetry(StepSemantics.AT_LEAST_ONCE_ASYNC)
            .build();

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<OperationEndInfo> stepEnds = new CopyOnWriteArrayList<>();
    private final List<String> hookOrder = new CopyOnWriteArrayList<>();

    @Test
    void asyncStepReturnsBeforeSucceedIsAnswered() throws Exception {
        var client = new BlockingClient(OperationType.STEP, OperationAction.SUCCEED);
        var stepReturned = new CountDownLatch(1);
        BiFunction<String, DurableContext, String> handler = (input, ctx) -> {
            var result = ctx.step("work", String.class, stepCtx -> "done", ASYNC);
            stepReturned.countDown();
            return result;
        };

        var output = CompletableFuture.supplyAsync(() -> execute(handler, config(client)));

        assertTrue(client.blocked.await(5, SECONDS));
        assertTrue(stepReturned.await(5, SECONDS), "the step must return while SUCCEED is unanswered");
        client.release.countDown();
        assertEquals(ExecutionStatus.SUCCEEDED, output.get().status());
        assertEquals(
                OperationStatus.SUCCEEDED, client.getOperationByName("work").status());
    }

    @Test
    void asyncVoidStepReturnsNullBeforeSucceedIsAnswered() throws Exception {
        var client = new BlockingClient(OperationType.STEP, OperationAction.SUCCEED);
        var stepReturned = new CountDownLatch(1);
        BiFunction<String, DurableContext, String> handler = (input, ctx) -> {
            Void result = ctx.step("work", Void.class, stepCtx -> null, ASYNC);
            stepReturned.countDown();
            return result == null ? "null" : "not null";
        };

        var output = CompletableFuture.supplyAsync(() -> execute(handler, config(client)));

        assertTrue(stepReturned.await(5, SECONDS), "the step must return null while SUCCEED is unanswered");
        assertTrue(client.blocked.await(5, SECONDS));
        client.release.countDown();
        assertEquals(ExecutionStatus.SUCCEEDED, output.get().status());
        assertEquals("\"null\"", output.get().result());
        assertEquals(1, stepEnds.size());
    }

    @Test
    void waitForCallbackSubmitterUnderAsyncDefaultSuspends() throws Exception {
        var client = new BlockingClient(OperationType.STEP, OperationAction.SUCCEED);
        BiFunction<String, DurableContext, String> handler =
                (input, ctx) -> ctx.waitForCallback("approval", String.class, (callbackId, stepCtx) -> {});
        var config = config(client).toBuilder()
                .withDefaultStepSemantics(StepSemantics.AT_LEAST_ONCE_ASYNC)
                .build();

        var output = CompletableFuture.supplyAsync(() -> execute(handler, config));

        assertTrue(client.blocked.await(5, SECONDS));
        client.release.countDown();
        assertEquals(ExecutionStatus.PENDING, output.get().status());
    }

    @Test
    void atLeastOnceStepWaitsForSucceed() throws Exception {
        var client = new BlockingClient(OperationType.STEP, OperationAction.SUCCEED);
        var stepReturned = new CountDownLatch(1);
        BiFunction<String, DurableContext, String> handler = (input, ctx) -> {
            var result = ctx.step(
                    "work",
                    String.class,
                    stepCtx -> "done",
                    StepConfig.builder()
                            .semanticsPerRetry(StepSemantics.AT_LEAST_ONCE)
                            .build());
            stepReturned.countDown();
            return result;
        };

        var output = CompletableFuture.supplyAsync(() -> execute(handler, config(client)));

        assertTrue(client.blocked.await(5, SECONDS));
        assertFalse(stepReturned.await(300, MILLISECONDS), "the step must wait for SUCCEED");
        client.release.countDown();
        assertEquals(ExecutionStatus.SUCCEEDED, output.get().status());
    }

    @Test
    void asyncStepWaitsForFail() throws Exception {
        var client = new BlockingClient(OperationType.STEP, OperationAction.FAIL);
        var stepThrew = new CountDownLatch(1);
        BiFunction<String, DurableContext, String> handler = (input, ctx) -> {
            try {
                return ctx.step(
                        "work",
                        String.class,
                        stepCtx -> {
                            throw new IllegalStateException("step failed");
                        },
                        ASYNC.toBuilder()
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build());
            } catch (IllegalStateException e) {
                stepThrew.countDown();
                throw e;
            }
        };

        var output = CompletableFuture.supplyAsync(() -> execute(handler, config(client)));

        assertTrue(client.blocked.await(5, SECONDS));
        assertFalse(stepThrew.await(300, MILLISECONDS), "the step must wait for FAIL");
        client.release.countDown();
        assertEquals(ExecutionStatus.FAILED, output.get().status());
    }

    @Test
    void endHookFiresOnceWhenSucceedIsAnswered() throws Exception {
        var client = new BlockingClient(OperationType.STEP, OperationAction.SUCCEED);
        var stepReturned = new CountDownLatch(1);
        BiFunction<String, DurableContext, String> handler = (input, ctx) -> {
            var result = ctx.step("work", String.class, stepCtx -> "done", ASYNC);
            stepReturned.countDown();
            return result;
        };

        var output = CompletableFuture.supplyAsync(() -> execute(handler, config(client)));

        assertTrue(client.blocked.await(5, SECONDS));
        assertTrue(stepReturned.await(5, SECONDS));
        Thread.sleep(100);
        assertTrue(stepEnds.isEmpty(), "the end hook must wait for the SUCCEED answer");
        client.release.countDown();
        assertEquals(ExecutionStatus.SUCCEEDED, output.get().status());
        assertEquals(1, stepEnds.size());
        var end = stepEnds.get(0);
        assertEquals("SUCCEEDED", end.status());
        assertEquals("\"done\"", end.result());
        assertEquals(1, end.attempt());
        // The end hook reports the timestamps from the service answer.
        assertEquals(BlockingClient.SERVICE_TIME, end.startTimestamp());
        assertEquals(BlockingClient.SERVICE_TIME, end.endTimestamp());
        assertEquals(List.of("step end SUCCEEDED", "invocation SUCCEEDED"), hookOrder);
    }

    @Test
    void failedSucceedFiresNoSucceededEndHook() {
        var client = new LocalMemoryExecutionClient() {
            @Override
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> updates) {
                if (updates.stream().anyMatch(u -> u.action() == OperationAction.SUCCEED)) {
                    throw SdkClientException.create("checkpoint failed");
                }
                return super.checkpoint(arn, token, updates);
            }
        };
        BiFunction<String, DurableContext, String> handler =
                (input, ctx) -> ctx.step("work", String.class, stepCtx -> "done", ASYNC);

        assertThrows(UnrecoverableDurableExecutionException.class, () -> execute(handler, config(client)));

        assertTrue(stepEnds.stream().noneMatch(end -> "SUCCEEDED".equals(end.status())));
        assertEquals(List.of("invocation RETRYING"), events);
    }

    @Test
    void succeedIsSavedBeforeInvocationReportsSucceeded() {
        var client = new SlowClient();
        BiFunction<String, DurableContext, String> handler =
                (input, ctx) -> ctx.step("work", String.class, stepCtx -> "done", ASYNC);

        var output = execute(handler, config(client));

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals(List.of("saved STEP SUCCEED", "invocation SUCCEEDED"), events);
    }

    @Test
    void childStepSucceedIsSavedBeforeChildContextSucceed() {
        var client = new SlowClient();
        BiFunction<String, DurableContext, String> handler = (input, ctx) -> ctx.runInChildContext(
                "child", String.class, child -> child.step("work", String.class, stepCtx -> "done", ASYNC));

        var output = execute(handler, config(client));

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals(List.of("saved STEP SUCCEED", "saved CONTEXT SUCCEED", "invocation SUCCEEDED"), events);
    }

    @Test
    void suspensionWaitsForQueuedSucceed() {
        var waitSaved = new CountDownLatch(1);
        var client = new LocalMemoryExecutionClient() {
            @Override
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> updates) {
                var response = super.checkpoint(arn, token, updates);
                if (updates.stream().anyMatch(u -> u.type() == OperationType.WAIT)) waitSaved.countDown();
                return response;
            }
        };
        // The delay keeps the SUCCEED queued after the step thread ends. The handler thread then waits too, so no
        // thread is active.
        var config = config(client).toBuilder()
                .withCheckpointDelay(Duration.ofMillis(300))
                .build();
        BiFunction<String, DurableContext, String> handler = (input, ctx) -> {
            ctx.stepAsync(
                    "work",
                    String.class,
                    stepCtx -> {
                        try {
                            assertTrue(waitSaved.await(5, SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return "done";
                    },
                    ASYNC);
            ctx.wait("pause", Duration.ofSeconds(10));
            return "finished";
        };

        var output = execute(handler, config);

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertEquals(
                OperationStatus.SUCCEEDED, client.getOperationByName("work").status());
    }

    @Test
    void failedSucceedAfterHandlerReturnsRetriesTheInvocation() {
        var handlerReturned = new CountDownLatch(1);
        var client = new LocalMemoryExecutionClient() {
            @Override
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> updates) {
                if (updates.stream().anyMatch(u -> u.action() == OperationAction.SUCCEED)) {
                    try {
                        assertTrue(handlerReturned.await(5, SECONDS));
                        // Let the invocation select the handler result before the checkpoint fails.
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw SdkClientException.create("checkpoint failed");
                }
                return super.checkpoint(arn, token, updates);
            }
        };
        BiFunction<String, DurableContext, String> handler = (input, ctx) -> {
            var result = ctx.step("work", String.class, stepCtx -> "done", ASYNC);
            handlerReturned.countDown();
            return result;
        };

        var failure =
                assertThrows(UnrecoverableDurableExecutionException.class, () -> execute(handler, config(client)));

        assertTrue(failure.isRetryable());
        assertEquals(List.of("invocation RETRYING"), events);
    }

    @Test
    void transientSucceedFailureAfterHandlerThrowsRetriesTheInvocation() {
        var client = new FailSucceedAfterHandlerThrows(AwsServiceException.builder()
                .message("service unavailable")
                .awsErrorDetails(errorDetails(503, "ServiceException"))
                .build());

        var failure = assertThrows(
                UnrecoverableDurableExecutionException.class, () -> execute(client.handler(), config(client)));

        assertTrue(failure.isRetryable());
        assertEquals(List.of("invocation RETRYING"), events);
    }

    @Test
    void executionScopeSucceedFailureAfterHandlerThrowsAnswersFailed() {
        var client = new FailSucceedAfterHandlerThrows(AwsServiceException.builder()
                .message("invalid payload")
                .awsErrorDetails(errorDetails(400, "ValidationException"))
                .build());

        var output = execute(client.handler(), config(client));

        assertEquals(ExecutionStatus.FAILED, output.status());
        assertEquals(List.of("invocation FAILED"), events);
    }

    private static AwsErrorDetails errorDetails(int status, String code) {
        return AwsErrorDetails.builder()
                .errorCode(code)
                .sdkHttpResponse(SdkHttpResponse.builder().statusCode(status).build())
                .build();
    }

    /** Fails the async SUCCEED with the given error, after the handler threw. */
    private static final class FailSucceedAfterHandlerThrows extends LocalMemoryExecutionClient {
        private final CountDownLatch handlerThrew = new CountDownLatch(1);
        private final RuntimeException error;

        FailSucceedAfterHandlerThrows(RuntimeException error) {
            this.error = error;
        }

        BiFunction<String, DurableContext, String> handler() {
            return (input, ctx) -> {
                ctx.step("work", String.class, stepCtx -> "done", ASYNC);
                handlerThrew.countDown();
                throw new IllegalStateException("handler failed");
            };
        }

        @Override
        public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
            if (updates.stream().anyMatch(u -> u.action() == OperationAction.SUCCEED)) {
                try {
                    assertTrue(handlerThrew.await(5, SECONDS));
                    // Let the invocation select the handler failure before the checkpoint fails.
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw error;
            }
            return super.checkpoint(arn, token, updates);
        }
    }

    @Test
    void transientOrdinarySucceedFailureAfterHandlerReturnsRetriesTheInvocation() {
        var client = new FailOrdinarySucceedAfterHandlerReturns(AwsServiceException.builder()
                .message("service unavailable")
                .awsErrorDetails(errorDetails(503, "ServiceException"))
                .build());

        var failure = assertThrows(
                UnrecoverableDurableExecutionException.class, () -> execute(client.handler(), config(client)));

        assertTrue(failure.isRetryable());
        assertEquals(List.of("invocation RETRYING"), events);
    }

    @Test
    void executionScopeOrdinarySucceedFailureAfterHandlerReturnsAnswersFailed() {
        var client = new FailOrdinarySucceedAfterHandlerReturns(AwsServiceException.builder()
                .message("invalid payload")
                .awsErrorDetails(errorDetails(400, "ValidationException"))
                .build());

        var output = execute(client.handler(), config(client));

        assertEquals(ExecutionStatus.FAILED, output.status());
        assertEquals(List.of("invocation FAILED"), events);
    }

    /**
     * Holds the SUCCEED of an unawaited ordinary step until the handler returned the result of an async step, then
     * fails it with the given error. The async SUCCEED is queued behind it, so the failure ends the invocation first.
     */
    private static final class FailOrdinarySucceedAfterHandlerReturns extends LocalMemoryExecutionClient {
        private final CountDownLatch ordinaryBlocked = new CountDownLatch(1);
        private final CountDownLatch handlerReturned = new CountDownLatch(1);
        private final RuntimeException error;

        FailOrdinarySucceedAfterHandlerReturns(RuntimeException error) {
            this.error = error;
        }

        BiFunction<String, DurableContext, String> handler() {
            return (input, ctx) -> {
                ctx.stepAsync("ordinary", String.class, stepCtx -> "ignored");
                try {
                    assertTrue(ordinaryBlocked.await(5, SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                var result = ctx.step("work", String.class, stepCtx -> "done", ASYNC);
                handlerReturned.countDown();
                return result;
            };
        }

        @Override
        public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
            if (updates.stream().anyMatch(u -> u.action() == OperationAction.SUCCEED && "ordinary".equals(u.name()))) {
                ordinaryBlocked.countDown();
                try {
                    assertTrue(handlerReturned.await(5, SECONDS));
                    // Let the invocation select the handler result before the checkpoint fails.
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw error;
            }
            return super.checkpoint(arn, token, updates);
        }
    }

    @Test
    void transientSucceedFailureEndsAStepStartedAfterIt() {
        var client = new FailSucceedBeforeLaterStep(AwsServiceException.builder()
                .message("service unavailable")
                .awsErrorDetails(errorDetails(503, "ServiceException"))
                .build());

        var failure = assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> assertThrows(
                        UnrecoverableDurableExecutionException.class, () -> execute(client.handler(), config(client))));

        assertTrue(failure.isRetryable());
        assertEquals(List.of("invocation RETRYING"), events);
    }

    @Test
    void executionScopeSucceedFailureEndsAStepStartedAfterIt() {
        var client = new FailSucceedBeforeLaterStep(AwsServiceException.builder()
                .message("invalid payload")
                .awsErrorDetails(errorDetails(400, "ValidationException"))
                .build());

        var output = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> execute(client.handler(), config(client)));

        assertEquals(ExecutionStatus.FAILED, output.status());
        assertEquals(List.of("invocation FAILED"), events);
    }

    /**
     * Fails the SUCCEED of async step A with the given error. Only after the invocation selected that failure does the
     * handler start the ordinary step B.
     */
    private static final class FailSucceedBeforeLaterStep extends LocalMemoryExecutionClient {
        private final CountDownLatch stepReturned = new CountDownLatch(1);
        private final RuntimeException error;

        FailSucceedBeforeLaterStep(RuntimeException error) {
            this.error = error;
        }

        BiFunction<String, DurableContext, String> handler() {
            return (input, ctx) -> {
                ctx.step("a", String.class, stepCtx -> "done", ASYNC);
                stepReturned.countDown();
                var manager = ((DurableContextImpl) ctx).getExecutionManager();
                while (!manager.isExecutionCompletedExceptionally()) {
                    Thread.onSpinWait();
                }
                return ctx.step("b", String.class, stepCtx -> "done");
            };
        }

        @Override
        public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
            if (updates.stream().anyMatch(u -> u.action() == OperationAction.SUCCEED && "a".equals(u.name()))) {
                try {
                    assertTrue(stepReturned.await(5, SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw error;
            }
            return super.checkpoint(arn, token, updates);
        }
    }

    @Test
    void earlierTerminationKeepsItsCauseWhenQueuedSucceedIsSkipped() {
        var client = new LocalMemoryExecutionClient();
        // The delay keeps the SUCCEED queued until the termination below skips it.
        var config = config(client).toBuilder()
                .withCheckpointDelay(Duration.ofMillis(300))
                .build();
        var cause = new UnrecoverableDurableExecutionException(
                ErrorObject.builder().errorMessage("terminated").build(), true);
        BiFunction<String, DurableContext, String> handler = (input, ctx) -> {
            ctx.step("work", String.class, stepCtx -> "done", ASYNC);
            return ctx.step("terminate", String.class, stepCtx -> {
                throw cause;
            });
        };

        var failure = assertThrows(UnrecoverableDurableExecutionException.class, () -> execute(handler, config));

        assertSame(cause, failure);
        assertEquals(List.of("invocation RETRYING"), events);
    }

    @Test
    void startedAsyncStepRunsAgainOnReplay() {
        var client = new LocalMemoryExecutionClient();
        var runs = new AtomicInteger();
        BiFunction<String, DurableContext, String> handler = (input, ctx) -> ctx.step(
                "work",
                String.class,
                stepCtx -> {
                    runs.incrementAndGet();
                    return "done";
                },
                ASYNC);
        execute(handler, config(client));
        client.resetCheckpointToStarted("work");

        var output = DurableExecutor.execute(
                input(client.getAllOperations()), null, TypeToken.get(String.class), handler, config(client));

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals(2, runs.get());
    }

    private static DurableExecutionOutput execute(
            BiFunction<String, DurableContext, String> handler, DurableConfig config) {
        return DurableExecutor.execute(input(List.of()), null, TypeToken.get(String.class), handler, config);
    }

    private DurableConfig config(LocalMemoryExecutionClient client) {
        return DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withPlugins(info -> new DurableExecutionPlugin() {
                    public void onOperationEnd(OperationEndInfo end) {
                        if (!"STEP".equals(end.type())) return;
                        stepEnds.add(end);
                        hookOrder.add("step end " + end.status());
                    }

                    public void onInvocationEnd(InvocationEndInfo end) {
                        events.add("invocation " + end.invocationStatus());
                        hookOrder.add("invocation " + end.invocationStatus());
                    }
                })
                .build();
    }

    private static DurableExecutionInput input(List<Operation> recorded) {
        var operations = new ArrayList<Operation>();
        operations.add(Operation.builder()
                .id(EXECUTION_OP_ID)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build());
        recorded.stream().filter(op -> op.type() != OperationType.EXECUTION).forEach(operations::add);
        return new DurableExecutionInput(
                EXECUTION_ARN,
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operations).build());
    }

    /**
     * Holds the first checkpoint that carries the given update until the test releases it. Its answers carry
     * {@link #SERVICE_TIME} as start and end time, as the service sets them.
     */
    private static final class BlockingClient extends LocalMemoryExecutionClient {
        static final Instant SERVICE_TIME = Instant.parse("2026-01-01T00:00:00Z");
        private final OperationType type;
        private final OperationAction action;
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingClient(OperationType type, OperationAction action) {
            this.type = type;
            this.action = action;
        }

        @Override
        public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
            if (updates.stream().anyMatch(u -> u.type() == type && u.action() == action)) {
                blocked.countDown();
                try {
                    assertTrue(release.await(10, SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            var response = super.checkpoint(arn, token, updates);
            var operations = response.newExecutionState().operations().stream()
                    .map(op -> op.toBuilder()
                            .startTimestamp(SERVICE_TIME)
                            .endTimestamp(SERVICE_TIME)
                            .build())
                    .toList();
            return response.toBuilder()
                    .newExecutionState(response.newExecutionState().toBuilder()
                            .operations(operations)
                            .build())
                    .build();
        }
    }

    /** Answers each SUCCEED checkpoint slowly and records when the service saved it. */
    private final class SlowClient extends LocalMemoryExecutionClient {
        @Override
        public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
            var succeeds = updates.stream()
                    .filter(u -> u.action() == OperationAction.SUCCEED)
                    .toList();
            if (!succeeds.isEmpty()) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            var response = super.checkpoint(arn, token, updates);
            succeeds.forEach(u -> events.add("saved " + u.type() + " SUCCEED"));
            return response;
        }
    }
}
