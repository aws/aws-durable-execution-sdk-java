// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.StepSemantics;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

class OperationHookFatalIntegrationTest {
    @SuppressWarnings("removal")
    static Stream<Arguments> cases() {
        return Stream.of(
                        "step-start",
                        "step-end",
                        "child-start",
                        "nested-operation-start",
                        "operation-end",
                        "operation-change")
                .flatMap(stage -> Stream.of(false, true)
                        .flatMap(wrapped -> Stream.of(new InternalError("plugin fatal"), new ThreadDeath())
                                .map(fatal -> Arguments.of(stage, wrapped, fatal))));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void pluginFatalEscapesWithoutOperationRetryOrFailureCheckpoint(String stage, boolean wrapped, Error fatal)
            throws Exception {
        var fired = new AtomicBoolean();
        var hookWorker = new AtomicReference<Thread>();
        var escapedWorker = new AtomicReference<Thread>();
        var uncaught = new AtomicReference<Throwable>();
        var escaped = new CountDownLatch(1);
        var retries = new AtomicInteger();
        var endCalls = new AtomicInteger();
        var end = new AtomicReference<InvocationEndInfo>();
        var updates = new CopyOnWriteArrayList<OperationUpdate>();
        var client = new LocalMemoryExecutionClient() {
            @Override
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> batch) {
                updates.addAll(batch);
                return super.checkpoint(arn, token, batch);
            }
        };
        DurableExecutionPluginFactory observer = info -> new DurableExecutionPlugin() {
            @Override
            public void onInvocationEnd(InvocationEndInfo value) {
                endCalls.incrementAndGet();
                end.set(value);
            }
        };
        DurableExecutionPluginFactory faulty = info -> new DurableExecutionPlugin() {
            private void failAt(String point) {
                if (!stage.equals(point) || !fired.compareAndSet(false, true)) return;
                hookWorker.set(Thread.currentThread());
                Thread.currentThread().setUncaughtExceptionHandler((owner, failure) -> {
                    escapedWorker.set(owner);
                    uncaught.set(failure);
                    escaped.countDown();
                });
                if (wrapped) throw new CompletionException(new ExecutionException(fatal));
                throw fatal;
            }

            public void onUserFunctionStart(UserFunctionStartInfo value) {
                if ("work".equals(value.name())) failAt("step-start");
                if ("child".equals(value.name())) failAt("child-start");
            }

            public void onUserFunctionEnd(UserFunctionEndInfo value) {
                if ("work".equals(value.name())) failAt("step-end");
            }

            public void onOperationStart(OperationInfo value) {
                if ("work".equals(value.name())) failAt("nested-operation-start");
            }

            public void onOperationEnd(OperationEndInfo value) {
                if ("work".equals(value.name())) failAt("operation-end");
            }

            public void onOperationChange(OperationChangeInfo value) {
                if (value.updatedOperations().values().stream().anyMatch(op -> op.status() == OperationStatus.STARTED))
                    failAt("operation-change");
            }
        };
        var workers = Executors.newCachedThreadPool(task -> {
            var thread = new Thread(task, "operation-fatal-owner");
            thread.setDaemon(true);
            return thread;
        });
        var callers = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "operation-fatal-caller");
            thread.setDaemon(true);
            return thread;
        });
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withCheckpointDelay(Duration.ZERO)
                .withExecutorService(workers)
                .withPlugins(observer, faulty)
                .build();
        var step = StepConfig.builder()
                // Await START for the checkpoint-hook case so its fatal is known before end-hook dispatch.
                .semanticsPerRetry(
                        stage.equals("operation-change")
                                ? StepSemantics.AT_MOST_ONCE_PER_RETRY
                                : StepSemantics.AT_LEAST_ONCE_PER_RETRY)
                .retryStrategy((error, attempt) -> {
                    retries.incrementAndGet();
                    return RetryDecision.fail();
                })
                .build();
        try {
            var response = callers.submit(() -> DurableExecutor.execute(
                    input(),
                    null,
                    TypeToken.get(String.class),
                    (value, context) -> {
                        if (stage.equals("child-start") || stage.equals("nested-operation-start"))
                            return context.runInChildContext(
                                    "child",
                                    String.class,
                                    child -> child.step("work", String.class, childStep -> "ok", step));
                        return context.step("work", String.class, stepContext -> "ok", step);
                    },
                    config));
            var thrown = assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS));
            assertSame(fatal, thrown.getCause(), "plugin fatal identity must reach the invocation caller");
            assertTrue(fired.get());
            assertTrue(escaped.await(2, TimeUnit.SECONDS), "fatal must escape the actual hook worker");
            assertSame(hookWorker.get(), escapedWorker.get());
            assertSame(fatal, uncaught.get());
            assertEquals(0, retries.get(), "plugin fatal must bypass the user operation retry strategy");
            assertTrue(
                    updates.stream()
                            .noneMatch(update -> update.action() == OperationAction.FAIL
                                    || update.action() == OperationAction.RETRY),
                    "plugin fatal must not become a persisted user failure");
            assertEquals(1, endCalls.get());
            assertEquals(InvocationStatus.RETRYING, end.get().invocationStatus());
            assertSame(fatal, end.get().executionError());
        } finally {
            workers.shutdownNow();
            callers.shutdownNow();
        }
    }

    @Test
    void legacyUserBodyFatalStillUsesItsExistingOperationFailurePath() {
        var original = new InternalError("user body, not plugin instrumentation");
        var retries = new AtomicInteger();
        var updates = new CopyOnWriteArrayList<OperationUpdate>();
        var client = new LocalMemoryExecutionClient() {
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> batch) {
                updates.addAll(batch);
                return super.checkpoint(arn, token, batch);
            }
        };
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withCheckpointDelay(Duration.ZERO)
                .build();
        var step = StepConfig.builder()
                .retryStrategy((error, attempt) -> {
                    retries.incrementAndGet();
                    return RetryDecision.fail();
                })
                .build();
        var actual = assertThrows(
                InternalError.class,
                () -> DurableExecutor.execute(
                        input(),
                        null,
                        TypeToken.get(String.class),
                        (value, context) -> context.step(
                                "work",
                                String.class,
                                stepContext -> {
                                    throw original;
                                },
                                step),
                        config));
        assertNotSame(original, actual, "legacy body error is restored from its stored user-operation failure");
        assertEquals(1, retries.get());
        assertTrue(updates.stream().anyMatch(update -> update.action() == OperationAction.FAIL));
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
