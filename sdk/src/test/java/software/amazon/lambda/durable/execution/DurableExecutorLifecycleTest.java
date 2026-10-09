// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(10)
class DurableExecutorLifecycleTest {
    private enum HandlerOutcome {
        SUCCESS,
        FAILURE,
        RETRY,
        LARGE_RESULT
    }

    private final DurableExecutionClient client = mock(DurableExecutionClient.class);
    private final DurableExecutionPlugin plugin = mock(DurableExecutionPlugin.class);

    @ParameterizedTest
    @EnumSource(HandlerOutcome.class)
    void queuedRevocationOverridesHandlerOutcomeAndClearsPluginError(HandlerOutcome outcome) {
        when(client.checkpoint(any(), any(), any()))
                .thenReturn(CheckpointDurableExecutionResponse.builder().build());
        var handlerError =
                switch (outcome) {
                    case FAILURE -> new IllegalArgumentException("handler failed");
                    case RETRY ->
                        new UnrecoverableDurableExecutionException(
                                ErrorObject.builder().errorMessage("retry").build(), true);
                    default -> null;
                };
        var output = execute(
                config().withCheckpointDelay(Duration.ofHours(1)).build(),
                handlerWithQueuedCheckpoint(outcome, handlerError));

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
        assertNull(output.error());
        verify(client, times(1)).checkpoint(any(), any(), any());
        verify(plugin, times(1))
                .onInvocationEnd(argThat(info -> info.invocationStatus() == InvocationStatus.PENDING
                        && info.executionError() == null
                        && info.executionResult() == null));
    }

    @Test
    void queuedStepDrainsOnSingleThreadExecutor() throws Exception {
        var userExecutor = Executors.newSingleThreadExecutor();
        var caller = Executors.newSingleThreadExecutor();
        var handlerReady = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);
        var ran = new AtomicBoolean();
        try {
            var config = config().withDurableExecutionClient(TestUtils.createMockClient())
                    .withExecutorService(userExecutor)
                    .build();
            var invocation =
                    caller.submit(() -> execute(config, handlerWithQueuedStep(ran, handlerReady, releaseHandler)));
            await(handlerReady);
            assertThrows(TimeoutException.class, () -> invocation.get(100, TimeUnit.MILLISECONDS));
            releaseHandler.countDown();

            assertEquals(
                    ExecutionStatus.SUCCEEDED,
                    invocation.get(5, TimeUnit.SECONDS).status());
            assertTrue(ran.get());
            verify(plugin, times(1))
                    .onInvocationEnd(argThat(info -> info.invocationStatus() == InvocationStatus.SUCCEEDED));
        } finally {
            releaseHandler.countDown();
            caller.shutdownNow();
            userExecutor.shutdownNow();
        }
    }

    @Test
    void queuedStepSkipsUserCodeAfterRevocation() {
        var userExecutor = Executors.newSingleThreadExecutor();
        var ran = new AtomicBoolean();
        when(client.checkpoint(any(), any(), any()))
                .thenReturn(CheckpointDurableExecutionResponse.builder().build());
        try {
            var output = execute(config().withExecutorService(userExecutor).build(), (value, ctx) -> {
                ctx.stepAsync("queued", String.class, stepCtx -> {
                    ran.set(true);
                    return "done";
                });
                ctx.wait("revoke", Duration.ofSeconds(1));
                return "unreachable";
            });

            assertEquals(ExecutionStatus.PENDING, output.status());
            assertFalse(ran.get());
            verify(client, times(1)).checkpoint(any(), any(), any());
        } finally {
            userExecutor.shutdownNow();
        }
    }

    @Test
    void runningStepFinishesBeforePendingPluginEvent() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var revoked = new CountDownLatch(1);
        var finished = new AtomicBoolean();
        when(client.checkpoint(any(), any(), any())).thenAnswer(call -> {
            await(started);
            revoked.countDown();
            return CheckpointDurableExecutionResponse.builder().build();
        });
        var caller = Executors.newSingleThreadExecutor();
        try {
            var invocation =
                    caller.submit(() -> execute(config().build(), handlerWithRunningStep(started, release, finished)));
            await(revoked);
            assertThrows(TimeoutException.class, () -> invocation.get(100, TimeUnit.MILLISECONDS));
            verify(plugin, never()).onInvocationEnd(any());
            release.countDown();

            assertEquals(
                    ExecutionStatus.PENDING, invocation.get(5, TimeUnit.SECONDS).status());
            assertTrue(finished.get());
            verify(plugin, times(1))
                    .onInvocationEnd(argThat(info ->
                            info.invocationStatus() == InvocationStatus.PENDING && info.executionError() == null));
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void pollingStopsBeforeOutputSerialization() throws Exception {
        var serializing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var manager = new AtomicReference<ExecutionManager>();
        var caller = Executors.newSingleThreadExecutor();
        try {
            var config = config().withDurableExecutionClient(TestUtils.createMockClient())
                    .withSerDes(blockedSerializer(serializing, release))
                    .build();
            var invocation = caller.submit(() -> execute(config, (value, ctx) -> {
                manager.set(((DurableContextImpl) ctx).getExecutionManager());
                ctx.waitAsync("unawaited", Duration.ofHours(1));
                return "done";
            }));
            await(serializing);
            var latePoller =
                    manager.get().pollForOperationUpdates("late", Instant.now().plusSeconds(60));
            assertTrue(latePoller.isCompletedExceptionally());
            var error = assertThrows(CompletionException.class, latePoller::join);
            assertInstanceOf(SuspendExecutionException.class, error.getCause());
            release.countDown();

            assertEquals(
                    ExecutionStatus.SUCCEEDED,
                    invocation.get(5, TimeUnit.SECONDS).status());
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void terminalCheckpointWithoutTokenRetainsPreviouslyObservedPluginStateWithoutPagination() {
        var terminalOperation = executionOperation().toBuilder()
                .status(OperationStatus.SUCCEEDED)
                .build();
        when(client.checkpoint(any(), any(), any()))
                .thenReturn(CheckpointDurableExecutionResponse.builder()
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(terminalOperation)
                                .nextMarker("unused")
                                .build())
                        .build());

        var output = execute(config().build(), (value, ctx) -> "x".repeat(6 * 1024 * 1024));

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals("", output.result());
        verify(client, times(1)).checkpoint(any(), any(), any());
        verify(client, never()).getExecutionState(any(), any(), any());
        verify(plugin, times(1))
                .onInvocationEnd(argThat(info -> info.invocationStatus() == InvocationStatus.SUCCEEDED
                        && info.operations().get("exec").status() == OperationStatus.STARTED));
    }

    private DurableConfig.Builder config() {
        return DurableConfig.builder().withDurableExecutionClient(client).withPlugins(plugin);
    }

    private DurableExecutionOutput execute(DurableConfig config, BiFunction<String, DurableContext, String> handler) {
        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/lifecycle/exec",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(executionOperation())
                        .build());
        return DurableExecutor.execute(input, null, get(String.class), handler, config);
    }

    private Operation executionOperation() {
        return Operation.builder()
                .id("exec")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
    }

    private BiFunction<String, DurableContext, String> handlerWithQueuedCheckpoint(
            HandlerOutcome outcome, Throwable error) {
        return (value, ctx) -> {
            ((DurableContextImpl) ctx)
                    .getExecutionManager()
                    .sendOperationUpdate(OperationUpdate.builder()
                            .id("queued")
                            .type(OperationType.STEP)
                            .action(OperationAction.START)
                            .build());
            if (error != null) {
                ExceptionHelper.sneakyThrow(error);
            }
            return outcome == HandlerOutcome.LARGE_RESULT ? "x".repeat(6 * 1024 * 1024) : "done";
        };
    }

    private BiFunction<String, DurableContext, String> handlerWithQueuedStep(
            AtomicBoolean ran, CountDownLatch handlerReady, CountDownLatch releaseHandler) {
        return (value, ctx) -> {
            ctx.stepAsync("queued", String.class, stepCtx -> {
                ran.set(true);
                return "done";
            });
            handlerReady.countDown();
            await(releaseHandler);
            return "done";
        };
    }

    private BiFunction<String, DurableContext, String> handlerWithRunningStep(
            CountDownLatch started, CountDownLatch release, AtomicBoolean finished) {
        return (value, ctx) -> ctx.step("running", String.class, stepCtx -> {
            started.countDown();
            await(release);
            finished.set(true);
            return "done";
        });
    }

    private JacksonSerDes blockedSerializer(CountDownLatch serializing, CountDownLatch release) {
        return new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                serializing.countDown();
                await(release);
                return super.serialize(value);
            }
        };
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for the test latch");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
