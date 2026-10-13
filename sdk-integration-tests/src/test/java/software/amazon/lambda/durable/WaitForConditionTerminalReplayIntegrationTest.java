// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.exception.WaitForConditionFailedException;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

class WaitForConditionTerminalReplayIntegrationTest {
    private static final String CACHED_ERROR = "cached terminal error";

    static Stream<Arguments> terminals() {
        return Stream.of(
                        OperationStatus.FAILED,
                        OperationStatus.CANCELLED,
                        OperationStatus.TIMED_OUT,
                        OperationStatus.STOPPED)
                .flatMap(status -> Stream.of("absent", "empty", "stored")
                        .flatMap(details -> Stream.of("replay", "retry-response", "poll-response")
                                .map(delivery -> Arguments.of(status, details, delivery))));
    }

    @ParameterizedTest
    @MethodSource("terminals")
    void terminalSnapshotsReplayWithoutRepeatingChecksOrCheckpoints(
            OperationStatus status, String details, String delivery) throws Exception {
        var deliverWhileRunning = !delivery.equals("replay");
        var retried = new AtomicBoolean();
        var delivered = new AtomicBoolean();
        var pollEntered = new CountDownLatch(1);
        var releasePoll = new CountDownLatch(1);
        var terminal = new AtomicReference<Operation>();
        var client = new LocalMemoryExecutionClient() {
            @Override
            public CheckpointDurableExecutionResponse checkpoint(
                    String arn, String token, List<OperationUpdate> updates) {
                var response = super.checkpoint(arn, token, updates);
                var retry = updates.stream().anyMatch(u -> u.action() == OperationAction.RETRY);
                if (retry) retried.set(true);
                var returnTerminal = delivery.equals("retry-response") && retry
                        || delivery.equals("poll-response") && retried.get() && updates.isEmpty();
                if (!returnTerminal || !delivered.compareAndSet(false, true)) return response;
                if (delivery.equals("poll-response")) {
                    pollEntered.countDown();
                    await(releasePoll);
                }
                // Exercise an actual checkpoint-completion boundary with the backend's terminal snapshot.
                var condition = getOperationByName("condition");
                terminal.set(terminalSnapshot(condition, status, details));
                return response.toBuilder()
                        .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                .operations(terminal.get())
                                .build())
                        .build();
            }
        };
        var workers = Executors.newCachedThreadPool();
        var caller = Executors.newSingleThreadExecutor();
        var checks = new AtomicInteger();
        try {
            var config = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withDurableExecutionClient(client)
                    .withCheckpointDelay(Duration.ZERO)
                    .withPollingStrategy(attempt -> Duration.ZERO)
                    .build();
            var first = caller.submit(() -> execute(List.of(), config, checks, status, details, () -> {
                        if (delivery.equals("poll-response")) {
                            await(pollEntered);
                            releasePoll.countDown();
                        }
                    }))
                    .get(5, TimeUnit.SECONDS);
            if (deliverWhileRunning) assertHandled(first, status, details);
            else {
                assertEquals(ExecutionStatus.PENDING, first.status());
                terminal.set(terminalSnapshot(client.getOperationByName("condition"), status, details));
            }
            assertEquals(1, checks.get());
            var updates = client.getOperationUpdates().size();
            var history = List.of(terminal.get());
            var replay = caller.submit(() -> execute(history, config, checks, status, details, () -> {}))
                    .get(5, TimeUnit.SECONDS);
            assertHandled(replay, status, details);
            var repeated = caller.submit(() -> execute(history, config, checks, status, details, () -> {}))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(replay, repeated);
            assertEquals(1, checks.get(), "Neither cancelled polling nor completed replay may re-enter the predicate");
            assertEquals(updates, client.getOperationUpdates().size(), "Replay must not rewrite a terminal checkpoint");
        } finally {
            releasePoll.countDown();
            caller.shutdownNow();
            workers.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Controlled terminal poll was not released");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static DurableExecutionOutput execute(
            List<Operation> history,
            DurableConfig config,
            AtomicInteger checks,
            OperationStatus status,
            String details,
            Runnable beforeAwait) {
        var operations = new ArrayList<Operation>();
        operations.add(Operation.builder()
                .id("execution")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build());
        operations.addAll(history);
        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/terminal/execution",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operations).build());
        return DurableExecutor.execute(
                input,
                null,
                TypeToken.get(String.class),
                (value, context) -> {
                    try {
                        var future = context.waitForConditionAsync(
                                "condition",
                                Integer.class,
                                (state, step) -> {
                                    checks.incrementAndGet();
                                    return WaitForConditionResult.continuePolling(1);
                                },
                                WaitForConditionConfig.<Integer>builder()
                                        .initialState(0)
                                        .waitStrategy((state, attempt) -> Duration.ofSeconds(1))
                                        .build());
                        beforeAwait.run();
                        future.get();
                        return "unexpected success";
                    } catch (WaitForConditionFailedException failure) {
                        assertNotEquals("stored", details);
                        assertEquals(status, failure.getOperationStatus());
                        assertEquals("condition", failure.getOperation().name());
                        return status + ":no error details";
                    } catch (IllegalStateException failure) {
                        assertEquals("stored", details);
                        assertEquals(CACHED_ERROR, failure.getMessage());
                        return CACHED_ERROR;
                    }
                },
                config);
    }

    private static void assertHandled(DurableExecutionOutput output, OperationStatus status, String details) {
        assertEquals(
                ExecutionStatus.SUCCEEDED,
                output.status(),
                () -> output.error() == null
                        ? "No error payload"
                        : output.error().errorType() + ": " + output.error().errorMessage());
        assertEquals(
                new JacksonSerDes().serialize(details.equals("stored") ? CACHED_ERROR : status + ":no error details"),
                output.result());
    }

    private static Operation terminalSnapshot(Operation pending, OperationStatus status, String details) {
        StepDetails snapshot = null;
        if (details.equals("empty")) snapshot = StepDetails.builder().attempt(1).build();
        else if (details.equals("stored"))
            snapshot = StepDetails.builder()
                    .attempt(1)
                    .error(ErrorObject.builder()
                            .errorType(IllegalStateException.class.getName())
                            .errorMessage(CACHED_ERROR)
                            .errorData(new JacksonSerDes().serialize(new IllegalStateException(CACHED_ERROR)))
                            .build())
                    .build();
        return pending.toBuilder().status(status).stepDetails(snapshot).build();
    }
}
