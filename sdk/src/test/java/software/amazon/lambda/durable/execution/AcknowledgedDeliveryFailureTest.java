// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateResponse;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;

@Timeout(15)
class AcknowledgedDeliveryFailureTest {
    private static final String ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/ack/execution";
    private static final String RESULT = "x".repeat(6 * 1024 * 1024 + 100);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void metadataFailureSurvivesCallerInterruptionDuringEnd(boolean cancelledMetadata) throws Exception {
        RuntimeException failure = cancelledMetadata
                ? new CancellationException("metadata request cancelled")
                : new IllegalStateException("metadata store failed");
        var observed = run(failure, true);
        assertSame(failure, observed.failure(), "Terminal ACK must not hide an unrelated delivery failure");
        assertNull(observed.output());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void causelessMetadataFailureRetainsItsIdentity(boolean interruptCaller) throws Exception {
        var failure = new CompletionException("metadata failure without a cause", null);
        var observed = run(failure, interruptCaller);
        assertSame(failure, observed.failure(), "A non-null delivery error must not become successful ACK delivery");
        assertNull(observed.output());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void applicationExecutionExceptionRemainsTheDeliveryFailure(boolean interruptCaller) throws Exception {
        var applicationFailure = new ExecutionException("application wrapper", new IllegalStateException("metadata"));
        try (var fixture = new Fixture(new CompletionException(applicationFailure), applicationFailure)) {
            var observed = fixture.observe(interruptCaller);
            assertSame(applicationFailure, observed.failure());
            assertNull(observed.output());
        }
    }

    @Test
    void metadataFailureWithoutCallerInterruptionStillEscapes() throws Exception {
        var failure = new IllegalStateException("metadata store failed");
        var observed = run(failure, false);
        assertSame(failure, observed.failure());
        assertNull(observed.output());
    }

    @Test
    void acknowledgedResultStillWinsOverCallerInterruptionAlone() throws Exception {
        var observed = run(null, true);
        assertNull(observed.failure());
        assertEquals(ExecutionStatus.SUCCEEDED, observed.output().status());
        assertEquals("", observed.output().result(), "The acknowledged large payload is not returned twice");
        assertNull(observed.output().error());
    }

    private static Observed run(RuntimeException metadataFailure, boolean interruptCaller) throws Exception {
        try (var fixture = new Fixture(metadataFailure)) {
            return fixture.observe(interruptCaller);
        }
    }

    private static final class Fixture implements AutoCloseable, DurableExecutionPlugin {
        private final ExecutorService users = Executors.newSingleThreadExecutor();
        private final ExecutorService callers = Executors.newSingleThreadExecutor();
        private final AtomicReference<Thread> caller = new AtomicReference<>();
        private final AtomicReference<Thread> owner = new AtomicReference<>();
        private final AtomicReference<InvocationEndInfo> endInfo = new AtomicReference<>();
        private final CountDownLatch enteredEnd = new CountDownLatch(1);
        private final CountDownLatch releaseEnd = new CountDownLatch(1);
        private final CountDownLatch endFinished = new CountDownLatch(1);
        private final AtomicBoolean endInterrupted = new AtomicBoolean();
        private final AtomicBoolean callerInterrupted = new AtomicBoolean();
        private final AtomicInteger endCalls = new AtomicInteger();
        private final AtomicReference<List<OperationUpdate>> updates = new AtomicReference<>();
        private final Throwable expectedFailure;
        private final DurableExecutionClient client;
        private final DurableConfig config;

        private Fixture(RuntimeException metadataFailure) {
            this(metadataFailure, metadataFailure);
        }

        private Fixture(RuntimeException metadataFailure, Throwable expectedFailure) {
            this.expectedFailure = expectedFailure;
            client = client(metadataFailure, updates);
            config = DurableConfig.builder()
                    .withExecutorService(users)
                    .withDurableExecutionClient(client)
                    .withCheckpointDelay(Duration.ZERO)
                    .withPlugins(ignoredInfo -> this)
                    .build();
        }

        @Override
        public void onInvocationStart(InvocationInfo info) {
            owner.set(Thread.currentThread());
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            assertSame(owner.get(), Thread.currentThread(), "End remains on its Start owner");
            endInfo.set(info);
            endCalls.incrementAndGet();
            enteredEnd.countDown();
            try {
                assertTrue(releaseEnd.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException expected) {
                endInterrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                endFinished.countDown();
            }
        }

        private Future<DurableExecutionOutput> invoke() {
            return callers.submit(() -> {
                caller.set(Thread.currentThread());
                try {
                    return DurableExecutor.execute(
                            input(), null, TypeToken.get(String.class), (value, context) -> RESULT, config);
                } finally {
                    callerInterrupted.set(Thread.interrupted());
                }
            });
        }

        private Observed observe(boolean interruptCaller) throws Exception {
            var invocation = invoke();
            assertTrue(enteredEnd.await(5, TimeUnit.SECONDS), "The controlled End hook must start");
            var status = expectedFailure == null ? InvocationStatus.SUCCEEDED : InvocationStatus.RETRYING;
            assertEquals(status, endInfo.get().invocationStatus());
            assertSame(expectedFailure, endInfo.get().executionError());
            if (interruptCaller) caller.get().interrupt();
            else releaseEnd.countDown();
            var observed = resultOf(invocation);
            assertLifecycle(interruptCaller);
            assertTransport();
            return observed;
        }

        private void assertLifecycle(boolean interrupted) {
            assertEquals(0, endFinished.getCount(), "The cooperative End owner exits before caller completion");
            assertEquals(1, endCalls.get());
            assertEquals(interrupted, endInterrupted.get());
            assertEquals(interrupted, callerInterrupted.get(), "Runtime caller interruption is restored");
        }

        private void assertTransport() {
            verify(client, times(1)).checkpoint(any(), any(), any());
            verify(client, times(1)).getExecutionState(ARN, "accepted-token", "metadata");
            assertEquals(1, updates.get().size());
            var update = updates.get().get(0);
            assertEquals(OperationType.EXECUTION, update.type());
            assertEquals(OperationAction.SUCCEED, update.action());
            assertEquals("execution", update.id());
            assertEquals('"' + RESULT + '"', update.payload());
        }

        @Override
        public void close() throws InterruptedException {
            releaseEnd.countDown();
            users.shutdownNow();
            callers.shutdownNow();
            assertTrue(users.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Observed resultOf(Future<DurableExecutionOutput> invocation) throws Exception {
        try {
            return new Observed(invocation.get(5, TimeUnit.SECONDS), null);
        } catch (ExecutionException failed) {
            return new Observed(null, failed.getCause());
        }
    }

    private static DurableExecutionClient client(
            RuntimeException metadataFailure, AtomicReference<List<OperationUpdate>> updates) {
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any())).thenAnswer(call -> {
            updates.set(List.copyOf(call.getArgument(2)));
            return CheckpointDurableExecutionResponse.builder()
                    .checkpointToken("accepted-token")
                    .newExecutionState(CheckpointUpdatedExecutionState.builder()
                            .nextMarker("metadata")
                            .build())
                    .build();
        });
        when(client.getExecutionState(any(), any(), any())).thenAnswer(call -> {
            if (metadataFailure != null) throw metadataFailure;
            return GetDurableExecutionStateResponse.builder().build();
        });
        return client;
    }

    private static DurableExecutionInput input() {
        var execution = Operation.builder()
                .id("execution")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        return new DurableExecutionInput(
                ARN,
                "token",
                CheckpointUpdatedExecutionState.builder().operations(execution).build());
    }

    private record Observed(DurableExecutionOutput output, Throwable failure) {}
}
