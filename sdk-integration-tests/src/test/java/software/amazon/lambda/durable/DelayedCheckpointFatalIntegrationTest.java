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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.StepSemantics;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

@Timeout(10)
class DelayedCheckpointFatalIntegrationTest {
    @SuppressWarnings("removal")
    static Stream<Arguments> failures() {
        return Stream.of(false, true)
                .flatMap(wrapped -> Stream.of(new InternalError("external plugin fatal"), new ThreadDeath())
                        .map(fatal -> Arguments.of(wrapped, fatal)));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void externalFatalSettlesDelayedAtMostOnceStartBeforeItsTimer(boolean wrapped, Error fatal) throws Exception {
        var startQueued = new CountDownLatch(1);
        var startFuture = new AtomicReference<CompletableFuture<Void>>();
        var bodies = new AtomicInteger();
        var backendCalls = new AtomicInteger();
        var workers = Executors.newCachedThreadPool(task -> {
            var thread = new Thread(task, "delayed-start-owner");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((owner, failure) -> {});
            return thread;
        });
        DurableExecutionPluginFactory fault = ignored -> new DurableExecutionPlugin() {
            public void onOperationStart(OperationInfo info) {
                if (!"trigger".equals(info.name())) return;
                if (wrapped) throw new CompletionException(new ExecutionException(fatal));
                throw fatal;
            }
        };
        var config = DurableConfig.builder()
                .withExecutorService(workers)
                .withCheckpointDelay(Duration.ofMinutes(1))
                .withDurableExecutionClient(new LocalMemoryExecutionClient() {
                    public CheckpointDurableExecutionResponse checkpoint(
                            String arn, String token, List<OperationUpdate> batch) {
                        backendCalls.incrementAndGet();
                        return super.checkpoint(arn, token, batch);
                    }
                })
                .withPlugins(fault)
                .build();
        var input = input();
        var manager = new ExecutionManager(input, config, null) {
            public CompletableFuture<Void> sendOperationUpdate(OperationUpdate update) {
                var result = super.sendOperationUpdate(update);
                if ("blocked".equals(update.name()) && update.action() == OperationAction.START) {
                    startFuture.set(result);
                    startQueued.countDown();
                }
                return result;
            }
        };
        try {
            manager.registerActiveThread(null);
            manager.setCurrentThreadContext(new ThreadContext(null, ThreadType.CONTEXT));
            manager.getPluginRunner()
                    .onInvocationStart(new InvocationInfo("request", input.durableExecutionArn(), true, Instant.EPOCH));
            var context = DurableContextImpl.createRootContext(manager, config, null);
            BaseContextImpl.setCurrentContext(context);
            context.stepAsync(
                    "blocked",
                    String.class,
                    step -> {
                        bodies.incrementAndGet();
                        return "unexpected";
                    },
                    StepConfig.builder()
                            .semanticsPerRetry(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                            .build());
            assertTrue(startQueued.await(3, TimeUnit.SECONDS));
            assertSame(
                    fatal,
                    assertThrows(Error.class, () -> context.stepAsync("trigger", String.class, step -> "unreachable")));
            assertSame(
                    fatal,
                    assertThrows(
                                    ExecutionException.class,
                                    () -> startFuture.get().get(500, TimeUnit.MILLISECONDS))
                            .getCause());
            assertEquals(0, bodies.get());
            assertEquals(0, backendCalls.get());
        } finally {
            // Keep the negative control bounded even before the production abort path exists.
            if (startFuture.get() != null) startFuture.get().completeExceptionally(fatal);
            workers.shutdown();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            try {
                manager.close();
            } catch (CompletionException failure) {
                assertSame(fatal, failure.getCause());
            }
            BaseContextImpl.setCurrentContext(null);
        }
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
