// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.StepSemantics;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

class PluginFatalQueuedCheckpointTest {
    @SuppressWarnings("removal")
    static Stream<Arguments> fatalCases() {
        return Stream.of(false, true)
                .flatMap(wrapped -> Stream.of(new InternalError("concurrent hook"), new ThreadDeath())
                        .map(fatal -> Arguments.of(wrapped, fatal)));
    }

    @ParameterizedTest
    @MethodSource("fatalCases")
    void queuedAtMostOnceStartCannotRunItsBodyAfterAnotherHookFails(boolean wrapped, Error fatal) throws Exception {
        var checkpointEntered = new CountDownLatch(1);
        var releaseCheckpoint = new CountDownLatch(1);
        var queuedBodies = new AtomicInteger();
        var ends = new AtomicInteger();
        var updates = new CopyOnWriteArrayList<OperationUpdate>();
        var client = new LocalMemoryExecutionClient() {
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> batch) {
                updates.addAll(batch);
                if (batch.stream()
                        .anyMatch(update ->
                                "barrier".equals(update.name()) && update.action() == OperationAction.START)) {
                    checkpointEntered.countDown();
                    await(releaseCheckpoint);
                }
                return super.checkpoint(arn, token, batch);
            }
        };
        DurableExecutionPluginFactory fault = info -> new DurableExecutionPlugin() {
            public void onUserFunctionStart(UserFunctionStartInfo start) {
                if (!"trigger".equals(start.name())) return;
                if (wrapped) throw new CompletionException(new ExecutionException(fatal));
                throw fatal;
            }

            public void onInvocationEnd(InvocationEndInfo end) {
                ends.incrementAndGet();
                releaseCheckpoint.countDown();
            }
        };
        var callers = Executors.newSingleThreadExecutor(task -> daemon(task, "queued-checkpoint-caller"));
        var workers = Executors.newCachedThreadPool(task -> daemon(task, "queued-checkpoint-worker"));
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withExecutorService(workers)
                .withCheckpointDelay(Duration.ZERO)
                .withPlugins(fault)
                .build();
        var atMostOnce = StepConfig.builder()
                .semanticsPerRetry(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                .build();
        try {
            var response = callers.submit(() -> DurableExecutor.execute(
                    input(),
                    null,
                    TypeToken.get(String.class),
                    (value, context) -> {
                        context.stepAsync("barrier", String.class, step -> "barrier", atMostOnce);
                        await(checkpointEntered);
                        var queued = context.stepAsync(
                                "queued",
                                String.class,
                                step -> {
                                    queuedBodies.incrementAndGet();
                                    return "must not run";
                                },
                                atMostOnce);
                        context.stepAsync("trigger", String.class, step -> "unreachable");
                        return queued.get();
                    },
                    config));
            assertSame(
                    fatal,
                    assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS))
                            .getCause());
            assertEquals(1, ends.get());
            assertTrue(
                    updates.stream()
                            .noneMatch(update ->
                                    "queued".equals(update.name()) && update.action() == OperationAction.START),
                    "the queued START must never reach the backend after the fatal");
            assertEquals(0, queuedBodies.get(), "a skipped START must not authorize the at-most-once user function");
        } finally {
            releaseCheckpoint.countDown();
            workers.shutdownNow();
            callers.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private static Thread daemon(Runnable task, String name) {
        var thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static DurableExecutionInput input() {
        var operation = Operation.builder()
                .id("id")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/id",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operation).build());
    }
}
