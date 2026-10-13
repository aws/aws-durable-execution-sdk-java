// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static software.amazon.lambda.durable.TypeToken.get;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.operation.BaseDurableOperation;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.serde.JacksonSerDes;

@Timeout(30)
class DurableExecutorLifecycleRegressionTest {
    @Test
    void revocationMustWaitForRootHandlerBeforeReturning() throws Exception {
        try (var scenario = new RootHandlerScenario(false)) {
            scenario.awaitFirstCheckpoint();
            assertThrows(
                    TimeoutException.class,
                    () -> scenario.invocation.get(500, TimeUnit.MILLISECONDS),
                    "Invocation must not return while its root handler is still running");

            scenario.releaseRoot.countDown();
            scenario.invocation.get(5, TimeUnit.SECONDS);
            assertEquals(0, scenario.rootFinished.getCount());
        }
    }

    @Test
    void laterStepMustUnwindAfterTokenWithdrawal() throws Exception {
        try (var scenario = new RootHandlerScenario(true)) {
            scenario.awaitFirstCheckpoint();
            scenario.releaseRoot.countDown();
            assertTrue(
                    scenario.rootFinished.await(5, TimeUnit.SECONDS),
                    "Root remains blocked on a later operation after token withdrawal");
            scenario.invocation.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void failureSerializationStillFiresInvocationEnd() {
        var ended = new AtomicInteger();
        var serializationFailure = new IllegalStateException("Cannot serialize failure");
        var config = DurableConfig.builder()
                .withDurableExecutionClient(mock(DurableExecutionClient.class))
                .withSerDes(failingErrorSerializer(serializationFailure))
                .withPlugins(recordingPlugin(ended))
                .build();

        var actual = assertThrows(
                IllegalStateException.class,
                () -> execute(config, (value, ctx) -> {
                    throw new IllegalArgumentException("handler failed");
                }));

        assertSame(serializationFailure, actual);
        assertEquals(1, ended.get(), "Error serialization must not suppress the invocation-end hook");
    }

    private static JacksonSerDes failingErrorSerializer(RuntimeException failure) {
        return new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                if (value instanceof Throwable) {
                    throw failure;
                }
                return super.serialize(value);
            }
        };
    }

    private static DurableExecutionPlugin recordingPlugin(AtomicInteger ended) {
        return new DurableExecutionPlugin() {
            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                ended.incrementAndGet();
            }
        };
    }

    private static DurableExecutionOutput execute(
            DurableConfig config, BiFunction<String, DurableContext, String> handler) {
        var execution = Operation.builder()
                .id("exec")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/lifecycle/exec",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(execution).build());
        return DurableExecutor.execute(input, null, get(String.class), handler, config);
    }

    private static class RootHandlerScenario implements AutoCloseable {
        private final CountDownLatch rootPaused = new CountDownLatch(1);
        private final CountDownLatch releaseRoot = new CountDownLatch(1);
        private final CountDownLatch continuationReached = new CountDownLatch(1);
        private final CountDownLatch rootFinished = new CountDownLatch(1);
        private final AtomicReference<BaseDurableOperation> firstOperation = new AtomicReference<>();
        private final AtomicReference<BaseDurableOperation> lateOperation = new AtomicReference<>();
        private final ExecutorService userExecutor = Executors.newFixedThreadPool(2);
        private final ExecutorService caller = Executors.newSingleThreadExecutor();
        private final Future<DurableExecutionOutput> invocation;

        private RootHandlerScenario(boolean startLaterStep) {
            var config = DurableConfig.builder()
                    .withDurableExecutionClient(revokingClient())
                    .withExecutorService(userExecutor)
                    .build();
            invocation = caller.submit(() -> execute(config, (value, context) -> runHandler(context, startLaterStep)));
        }

        private DurableExecutionClient revokingClient() {
            var delegate = TestUtils.createMockClient();
            var client = mock(DurableExecutionClient.class);
            var checkpoints = new AtomicInteger();
            when(client.checkpoint(any(), any(), any())).thenAnswer(call -> {
                await(rootPaused);
                var response = delegate.checkpoint(call.getArgument(0), call.getArgument(1), call.getArgument(2));
                // Preserve accepted operation state so the same test can run against the pre-change implementation.
                return checkpoints.incrementAndGet() == 1
                        ? response.toBuilder().checkpointToken(null).build()
                        : response;
            });
            return client;
        }

        private String runHandler(DurableContext context, boolean startLaterStep) {
            try {
                firstOperation.set((BaseDurableOperation) context.stepAsync("async", String.class, step -> "done"));
                rootPaused.countDown();
                // Hold the root between durable calls to make token withdrawal during orchestration reproducible.
                await(releaseRoot);
                if (startLaterStep) {
                    var next = context.stepAsync("next", String.class, step -> "next");
                    lateOperation.set((BaseDurableOperation) next);
                    continuationReached.countDown();
                    return next.get();
                }
                return "root-result";
            } finally {
                // Immediate suspension from stepAsync is also a valid way to unwind the root.
                continuationReached.countDown();
                rootFinished.countDown();
            }
        }

        private void awaitFirstCheckpoint() throws Exception {
            await(rootPaused);
            firstOperation.get().getRunningUserHandler().get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws InterruptedException {
            releaseRoot.countDown();
            try {
                await(continuationReached);
                var operation = lateOperation.get();
                if (operation != null) {
                    // Cleanup only: release the stranded future after the assertion so a failing test cannot leak it.
                    operation.getCompletionFuture().completeExceptionally(new SuspendExecutionException());
                }
                assertTrue(rootFinished.await(5, TimeUnit.SECONDS), "Root handler did not exit during cleanup");
            } finally {
                caller.shutdownNow();
                userExecutor.shutdownNow();
                assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS), "Invocation executor did not terminate");
                assertTrue(userExecutor.awaitTermination(5, TimeUnit.SECONDS), "User executor did not terminate");
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for test coordination");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
