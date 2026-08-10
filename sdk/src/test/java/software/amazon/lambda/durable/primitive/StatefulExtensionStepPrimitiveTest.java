// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.primitive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.exception.NonDeterministicExecutionException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepFunction;
import software.amazon.lambda.durable.extension.ExtensionStepResult;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class StatefulExtensionStepPrimitiveTest {
    private static final String OPERATION_ID = "1";
    private static final String OPERATION_NAME = "test-wait-for-condition";
    private static final JacksonSerDes SERDES = new JacksonSerDes();

    private ExecutionManager executionManager;
    private DurableContextImpl durableContext;

    private static final class NormalizingSerDes implements SerDes {
        @Override
        public String serialize(Object value) {
            return "\"raw\"";
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            return (T) "normalized";
        }
    }

    @BeforeEach
    void setUp() {
        executionManager = mock(ExecutionManager.class);
        durableContext = mock(DurableContextImpl.class);
        when(durableContext.getExecutionManager()).thenReturn(executionManager);
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext("handler", ThreadType.CONTEXT));
        when(durableContext.getDurableConfig())
                .thenReturn(DurableConfig.builder()
                        .withExecutorService(Executors.newCachedThreadPool())
                        .build());
    }

    @Test
    void replaySucceededReturnsCachedResultWithoutCallingFunction() {
        var existing = operation(
                OperationStatus.SUCCEEDED, StepDetails.builder().result("42").build());
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(existing);
        var called = new CountDownLatch(1);
        var operation = createOperation(state -> {
            called.countDown();
            return ExtensionStepResult.succeed(state);
        });

        operation.execute();

        assertEquals(42, operation.get());
        assertEquals(1, called.getCount());
    }

    @Test
    void replayFailedThrowsOriginalException() {
        var original = new IllegalArgumentException("bad state");
        var error = ErrorObject.builder()
                .errorType(IllegalArgumentException.class.getName())
                .errorMessage("bad state")
                .errorData(SERDES.serialize(original))
                .stackTrace(List.of("com.example.Test|method|Test.java|42"))
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(operation(
                        OperationStatus.FAILED,
                        StepDetails.builder().error(error).build()));

        var operation = createOperation(ExtensionStepResult::succeed);
        operation.execute();

        assertEquals(
                "bad state",
                assertThrows(IllegalArgumentException.class, operation::get).getMessage());
    }

    @Test
    void replayFailedFallsBackToStepFailedException() {
        var error = ErrorObject.builder()
                .errorType("com.example.MissingException")
                .errorMessage("failed")
                .stackTrace(List.of("com.example.Test|method|Test.java|1"))
                .build();
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(operation(
                        OperationStatus.FAILED,
                        StepDetails.builder().error(error).build()));

        var operation = createOperation(ExtensionStepResult::succeed);
        operation.execute();

        assertThrows(StepFailedException.class, operation::get);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"STARTED, 10", "READY, 5"})
    void replayStartedOrReadyResumesWithCheckpointedState(OperationStatus status, int expectedState) throws Exception {
        assertResumes(status, expectedState);
    }

    @Test
    void replayPendingPollsUntilReadyAndResumes() throws Exception {
        var pending = operation(
                OperationStatus.PENDING,
                StepDetails.builder().attempt(1).result("5").build());
        var ready = operation(
                OperationStatus.READY,
                StepDetails.builder().attempt(1).result("5").build());
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(pending);
        when(executionManager.pollForOperationUpdates(OPERATION_ID))
                .thenReturn(CompletableFuture.completedFuture(ready));
        var called = new CountDownLatch(1);
        var operation = createOperation(state -> {
            called.countDown();
            return ExtensionStepResult.succeed(state);
        });

        operation.execute();

        assertTrue(called.await(2, TimeUnit.SECONDS));
    }

    @Test
    void replayWithoutCheckpointStateUsesInitialState() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(operation(
                        OperationStatus.STARTED,
                        StepDetails.builder().attempt(0).build()));
        var called = new CountDownLatch(1);
        var operation = createOperation(
                state -> {
                    assertEquals(42, state);
                    called.countDown();
                    return ExtensionStepResult.succeed(state);
                },
                42);

        operation.execute();

        assertTrue(called.await(2, TimeUnit.SECONDS));
    }

    @Test
    void exceptionRetryWithoutStateDoesNotCheckpointPayload() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(null);
        when(executionManager.pollForOperationUpdates(OPERATION_ID)).thenReturn(new CompletableFuture<>());
        var retryUpdate = new AtomicReference<OperationUpdate>();
        var retrySent = new CountDownLatch(1);
        when(executionManager.sendOperationUpdate(any())).thenAnswer(invocation -> {
            var update = invocation.<OperationUpdate>getArgument(0);
            if (update.action() == OperationAction.RETRY) {
                retryUpdate.set(update);
                retrySent.countDown();
            }
            return CompletableFuture.completedFuture(null);
        });
        var operation = createOperationWithConfig(
                state -> {
                    throw new IllegalStateException("retry");
                },
                ExtensionStepConfig.<Integer>builder()
                        .serDes(SERDES)
                        .retryStrategy(
                                (error, state, attempt) -> ExtensionStepResult.retry(state, Duration.ofSeconds(1)))
                        .build());

        operation.execute();

        assertTrue(retrySent.await(2, TimeUnit.SECONDS));
        assertNull(retryUpdate.get().payload());
        assertEquals(
                IllegalStateException.class.getName(), retryUpdate.get().error().errorType());
    }

    @Test
    void retryDelayUsesCheckpointNormalizedState() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID)).thenReturn(null);
        when(executionManager.pollForOperationUpdates(OPERATION_ID)).thenReturn(new CompletableFuture<>());
        var retryUpdate = new AtomicReference<OperationUpdate>();
        var retrySent = new CountDownLatch(1);
        when(executionManager.sendOperationUpdate(any())).thenAnswer(invocation -> {
            var update = invocation.<OperationUpdate>getArgument(0);
            if (update.action() == OperationAction.RETRY) {
                retryUpdate.set(update);
                retrySent.countDown();
            }
            return CompletableFuture.completedFuture(null);
        });
        var strategyState = new AtomicReference<String>();
        var operation = new StepPrimitive<>(
                new OperationIdentifier(
                        OPERATION_ID,
                        OPERATION_NAME,
                        OperationType.STEP,
                        OperationSubType.WAIT_FOR_CONDITION.getValue()),
                state -> ExtensionStepResult.retryAfterNormalization("raw", normalized -> {
                    strategyState.set(normalized);
                    return Duration.ofSeconds(7);
                }),
                TypeToken.get(String.class),
                ExtensionStepConfig.<String>builder()
                        .serDes(new NormalizingSerDes())
                        .build(),
                durableContext);

        operation.execute();

        assertTrue(retrySent.await(2, TimeUnit.SECONDS));
        assertEquals("normalized", strategyState.get());
        assertEquals("\"raw\"", retryUpdate.get().payload());
        assertEquals(7, retryUpdate.get().stepOptions().nextAttemptDelaySeconds());
    }

    @Test
    void corruptReplayStateFailsBeforeCallingFunction() {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(operation(
                        OperationStatus.STARTED,
                        StepDetails.builder()
                                .attempt(1)
                                .result("not-valid-json!!!")
                                .build()));
        var called = new CountDownLatch(1);
        var operation = createOperation(state -> {
            called.countDown();
            return ExtensionStepResult.succeed(state);
        });

        assertThrows(SerDesException.class, operation::execute);
        assertEquals(1, called.getCount());
    }

    @Test
    void replayStillValidatesTypeNameAndStatus() {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .id(OPERATION_ID)
                        .name(OPERATION_NAME)
                        .type(OperationType.WAIT)
                        .status(OperationStatus.SUCCEEDED)
                        .build());
        assertThrows(NonDeterministicExecutionException.class, () -> createOperation(ExtensionStepResult::succeed)
                .execute());

        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(Operation.builder()
                        .id(OPERATION_ID)
                        .name("different")
                        .type(OperationType.STEP)
                        .status(OperationStatus.SUCCEEDED)
                        .build());
        assertThrows(NonDeterministicExecutionException.class, () -> createOperation(ExtensionStepResult::succeed)
                .execute());

        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(operation(OperationStatus.UNKNOWN_TO_SDK_VERSION, null));
        assertThrows(IllegalDurableOperationException.class, () -> createOperation(ExtensionStepResult::succeed)
                .execute());
    }

    private void assertResumes(OperationStatus status, int expectedState) throws Exception {
        when(executionManager.getOperationAndUpdateReplayState(OPERATION_ID))
                .thenReturn(operation(
                        status,
                        StepDetails.builder()
                                .attempt(1)
                                .result(String.valueOf(expectedState))
                                .build()));
        var called = new CountDownLatch(1);
        var operation = createOperation(state -> {
            assertEquals(expectedState, state);
            called.countDown();
            return ExtensionStepResult.succeed(state);
        });

        operation.execute();

        assertTrue(called.await(2, TimeUnit.SECONDS));
    }

    private StepPrimitive<Integer> createOperation(ExtensionStepFunction<Integer> function) {
        return createOperation(function, null);
    }

    private StepPrimitive<Integer> createOperation(ExtensionStepFunction<Integer> function, Integer initialState) {
        return createOperationWithConfig(
                function,
                ExtensionStepConfig.<Integer>builder()
                        .initialState(initialState)
                        .serDes(SERDES)
                        .build());
    }

    private StepPrimitive<Integer> createOperationWithConfig(
            ExtensionStepFunction<Integer> function, ExtensionStepConfig<Integer> config) {
        return new StepPrimitive<>(
                new OperationIdentifier(
                        OPERATION_ID,
                        OPERATION_NAME,
                        OperationType.STEP,
                        OperationSubType.WAIT_FOR_CONDITION.getValue()),
                function,
                TypeToken.get(Integer.class),
                config,
                durableContext);
    }

    private Operation operation(OperationStatus status, StepDetails details) {
        return Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.STEP)
                .subType(OperationSubType.WAIT_FOR_CONDITION.getValue())
                .status(status)
                .stepDetails(details)
                .build();
    }
}
