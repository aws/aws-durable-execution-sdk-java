// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

class PreparationFailureDiagnosticsTest {
    @ParameterizedTest
    @ValueSource(strings = {"serialize", "checkpoint"})
    void preparationCauseDeliveredToCallerRetainsEndError(String mode) throws Exception {
        var preparation = new IllegalStateException("output preparation failed");
        var endFailure = new AssertionError("End error");
        var end = new AtomicReference<InvocationEndInfo>();
        var endCalls = new AtomicInteger();
        var checkpoints = new AtomicInteger();
        var workers = Executors.newCachedThreadPool();
        var caller = Executors.newSingleThreadExecutor();
        var client = new LocalMemoryExecutionClient() {
            @Override
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> updates) {
                if (mode.equals("checkpoint")
                        && updates.stream().anyMatch(op -> op.type() == OperationType.EXECUTION)) {
                    checkpoints.incrementAndGet();
                    throw preparation;
                }
                return super.checkpoint(arn, token, updates);
            }
        };
        var serDes = new SerDes() {
            private final JacksonSerDes delegate = new JacksonSerDes();

            public String serialize(Object value) {
                if (mode.equals("serialize") && "result".equals(value)) throw preparation;
                return delegate.serialize(value);
            }

            public <T> T deserialize(String value, TypeToken<T> type) {
                return delegate.deserialize(value, type);
            }
        };
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withExecutorService(workers)
                .withSerDes(serDes)
                .withCheckpointDelay(Duration.ZERO)
                .withPlugins(new DurableExecutionPlugin() {
                    public void onInvocationEnd(InvocationEndInfo info) {
                        endCalls.incrementAndGet();
                        end.set(info);
                        throw endFailure;
                    }
                })
                .build();
        try {
            var result = caller.submit(() -> DurableExecutor.execute(
                    input(),
                    null,
                    TypeToken.get(String.class),
                    (value, context) -> mode.equals("checkpoint") ? "x".repeat(6 * 1024 * 1024) : "result",
                    config));
            var observed = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS))
                    .getCause();
            System.out.println("PREPARATION_DIAGNOSTICS mode=" + mode + " original=" + (observed == preparation)
                    + " suppressed=" + List.of(observed.getSuppressed()) + " End=" + endCalls.get());
            assertSame(preparation, observed);
            assertEquals(List.of(endFailure), List.of(observed.getSuppressed()));
            assertEquals(1, endCalls.get());
            assertEquals(InvocationStatus.RETRYING, end.get().invocationStatus());
            assertSame(preparation, end.get().executionError());
            assertEquals(mode.equals("checkpoint") ? 1 : 0, checkpoints.get());
        } finally {
            caller.shutdownNow();
            workers.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/diagnostics/execution",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("execution")
                                .type(OperationType.EXECUTION)
                                .status(OperationStatus.STARTED)
                                .startTimestamp(Instant.EPOCH)
                                .executionDetails(ExecutionDetails.builder()
                                        .inputPayload("\"input\"")
                                        .build())
                                .build())
                        .build());
    }
}
