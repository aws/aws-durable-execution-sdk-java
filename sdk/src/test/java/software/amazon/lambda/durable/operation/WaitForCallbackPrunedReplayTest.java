// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.CallbackSubmitterException;
import software.amazon.lambda.durable.exception.CallbackTimeoutException;
import software.amazon.lambda.durable.exception.ChildContextFailedException;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.execution.OperationIdGenerator;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.serde.JacksonSerDes;

/** Exercises the public wait API with the terminal parent snapshot returned after backend child pruning. */
@Timeout(15)
class WaitForCallbackPrunedReplayTest {
    private static final String NAME = "failed-wait";
    private static final String PARENT_ID = OperationIdGenerator.hashOperationId("1");
    private static final ErrorObject CHECKPOINT_ERROR = ErrorObject.builder()
            .errorType("RemoteFailure")
            .errorMessage("original parent failure")
            .errorData("{\"remote\":true}")
            .build();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "parent-only-null",
                "parent-only-empty",
                "parent-only-details",
                "missing-callback",
                "missing-callback-details",
                "missing-submitter-details",
                "missing-pending-submitter",
                "failed-callback-without-submitter",
                "timed-out-callback-without-submitter",
                "failed-submitter",
                "reconstructible-original",
                "complete-unrecognized-state"
            })
    void replayPreservesKnownFailureOrActualFailedParentWithoutExecutingBody(String scenario) throws Exception {
        var state = state(scenario);
        var checkpointClient = mock(DurableExecutionClient.class);
        var submitterCalls = new AtomicInteger();
        var observed = new AtomicReference<RuntimeException>();
        var workers = Executors.newSingleThreadExecutor();
        try {
            var config = DurableConfig.builder()
                    .withDurableExecutionClient(checkpointClient)
                    .withExecutorService(workers)
                    .build();
            var result = DurableExecutor.execute(
                    input(state),
                    null,
                    TypeToken.get(String.class),
                    (value, context) -> {
                        try {
                            context.waitForCallback(
                                    NAME, String.class, (callbackId, step) -> submitterCalls.incrementAndGet());
                            return "unexpected-success";
                        } catch (CallbackFailedException
                                | CallbackTimeoutException
                                | CallbackSubmitterException
                                | ChildContextFailedException
                                | IllegalArgumentException expectedPublicFailure) {
                            observed.set(expectedPublicFailure);
                            return "caught";
                        }
                    },
                    config);
            System.out.println("PRUNED_REPLAY scenario=" + scenario + " status=" + result.status()
                    + " observed="
                    + (observed.get() == null ? null : observed.get().getClass().getName())
                    + " error=" + result.error());
            assertEquals(0, submitterCalls.get());
            verifyNoInteractions(checkpointClient);
            if (scenario.equals("complete-unrecognized-state")) {
                assertEquals(ExecutionStatus.FAILED, result.status());
                assertEquals(
                        IllegalStateException.class.getName(), result.error().errorType());
                assertEquals("Unknown waitForCallback status", result.error().errorMessage());
                assertNull(observed.get());
                return;
            }
            assertEquals(ExecutionStatus.SUCCEEDED, result.status(), () -> "unexpected API failure: " + result.error());
            assertEquals("\"caught\"", result.result());
            assertNull(result.error());
            checkFailure(scenario, state, observed.get());
        } finally {
            workers.shutdown();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void checkFailure(String scenario, Snapshot state, RuntimeException observed) {
        if (scenario.equals("reconstructible-original")) {
            assertInstanceOf(IllegalArgumentException.class, observed);
            assertEquals("saved original", observed.getMessage());
        } else if (scenario.equals("failed-callback-without-submitter")) {
            var failure = assertInstanceOf(CallbackFailedException.class, observed);
            assertSame(state.children().get(0), failure.getOperation());
            assertSame(CHECKPOINT_ERROR, failure.getErrorObject());
            assertEquals("callback-id", failure.getCallbackId());
        } else if (scenario.equals("timed-out-callback-without-submitter")) {
            var failure = assertInstanceOf(CallbackTimeoutException.class, observed);
            assertSame(state.children().get(0), failure.getOperation());
            assertEquals("callback-id", failure.getCallbackId());
        } else if (scenario.equals("failed-submitter")) {
            var failure = assertInstanceOf(CallbackSubmitterException.class, observed);
            assertSame(state.children().get(0), failure.getOperation());
            var cause = assertInstanceOf(StepFailedException.class, failure.getCause());
            assertSame(state.children().get(1), cause.getOperation());
            assertSame(CHECKPOINT_ERROR, cause.getErrorObject());
        } else {
            var failure = assertInstanceOf(ChildContextFailedException.class, observed);
            assertSame(state.parent(), failure.getOperation(), "fallback must identify the actual checkpointed parent");
            assertSame(
                    state.parent().contextDetails().error(),
                    failure.getErrorObject(),
                    "retain the original checkpoint error");
            assertEquals(PARENT_ID, failure.getOperationId());
            assertEquals(OperationStatus.FAILED, failure.getOperationStatus());
            assertNull(failure.getCause(), "do not invent a callback/submitter category");
        }
    }

    private static Snapshot state(String scenario) {
        var error = scenario.equals("parent-only-null")
                ? null
                : scenario.equals("parent-only-empty") ? ErrorObject.builder().build() : CHECKPOINT_ERROR;
        if (scenario.equals("reconstructible-original")) {
            var original = new IllegalArgumentException("saved original");
            error = ErrorObject.builder()
                    .errorType(original.getClass().getName())
                    .errorMessage(original.getMessage())
                    .errorData(new JacksonSerDes().serialize(original))
                    .build();
        }
        var parent = Operation.builder()
                .id(PARENT_ID)
                .name(NAME)
                .type(OperationType.CONTEXT)
                .subType("WaitForCallback")
                .status(OperationStatus.FAILED)
                .startTimestamp(Instant.ofEpochSecond(1))
                .endTimestamp(Instant.ofEpochSecond(2))
                .contextDetails(ContextDetails.builder().error(error).build())
                .build();
        var children = switch (scenario) {
            case "missing-callback" -> List.of(submitter(OperationStatus.SUCCEEDED, true));
            case "missing-callback-details" ->
                List.of(callback(OperationStatus.FAILED, false), submitter(OperationStatus.SUCCEEDED, true));
            case "missing-submitter-details" ->
                List.of(callback(OperationStatus.STARTED, true), submitter(OperationStatus.FAILED, false));
            case "missing-pending-submitter" -> List.of(callback(OperationStatus.STARTED, true));
            case "failed-callback-without-submitter" -> List.of(callback(OperationStatus.FAILED, true));
            case "timed-out-callback-without-submitter" -> List.of(callback(OperationStatus.TIMED_OUT, true));
            case "failed-submitter" ->
                List.of(callback(OperationStatus.STARTED, true), submitter(OperationStatus.FAILED, true));
            case "complete-unrecognized-state" ->
                List.of(callback(OperationStatus.SUCCEEDED, true), submitter(OperationStatus.SUCCEEDED, true));
            default -> List.<Operation>of();
        };
        return new Snapshot(parent, children);
    }

    private static Operation callback(OperationStatus status, boolean details) {
        return Operation.builder()
                .id("callback")
                .parentId(PARENT_ID)
                .name(NAME + "-callback")
                .type(OperationType.CALLBACK)
                .subType("Callback")
                .status(status)
                .callbackDetails(
                        details
                                ? CallbackDetails.builder()
                                        .callbackId("callback-id")
                                        .error(CHECKPOINT_ERROR)
                                        .build()
                                : null)
                .build();
    }

    private static Operation submitter(OperationStatus status, boolean details) {
        return Operation.builder()
                .id("submitter")
                .parentId(PARENT_ID)
                .name(NAME + "-submitter")
                .type(OperationType.STEP)
                .subType("Step")
                .status(status)
                .stepDetails(
                        details ? StepDetails.builder().error(CHECKPOINT_ERROR).build() : null)
                .build();
    }

    private static DurableExecutionInput input(Snapshot state) {
        var execution = Operation.builder()
                .id("execution")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.ofEpochSecond(1))
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        var operations = new ArrayList<>(List.of(execution, state.parent()));
        operations.addAll(state.children());
        return new DurableExecutionInput(
                "arn:pruned/execution",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operations).build(),
                List.of());
    }

    private record Snapshot(Operation parent, List<Operation> children) {}
}
