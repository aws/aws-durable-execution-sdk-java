// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.exception.WaitForConditionFailedException;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepFunction;
import software.amazon.lambda.durable.extension.ExtensionStepResult;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class DurableWaitForConditionOperationImplementationTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void executeMapsPollingResultsToStatefulStepOutcomes() {
        var context = mock(ExtensionContext.class);
        var reservation = mock(ExtensionOperation.class);
        var future = mockStringFuture();
        var resultType = TypeToken.get(String.class);
        var serDes = new JacksonSerDes();
        var config = WaitForConditionConfig.<String>builder()
                .initialState("initial")
                .serDes(serDes)
                .waitStrategy((state, attempt) -> {
                    assertEquals("next", state);
                    assertEquals(2, attempt);
                    return Duration.ofSeconds(7);
                })
                .build();
        when(context.reserve("ready")).thenReturn(reservation);
        when(reservation.stepAsync(
                        eq(OperationSubType.WAIT_FOR_CONDITION.getValue()),
                        eq(resultType),
                        any(ExtensionStepFunction.class),
                        any(ExtensionStepConfig.class)))
                .thenReturn(future);

        var actual = DurableWaitForConditionOperation.waitForConditionAsync(
                context,
                "ready",
                resultType,
                (state, step) -> WaitForConditionResult.continuePolling("next"),
                config.toOperationConfig());

        assertEquals(future.get(), actual.get());
        var function = extensionFunction();
        var extensionConfig = ArgumentCaptor.forClass(ExtensionStepConfig.class);
        verify(reservation)
                .stepAsync(
                        eq(OperationSubType.WAIT_FOR_CONDITION.getValue()),
                        eq(resultType),
                        function.capture(),
                        extensionConfig.capture());
        assertEquals("initial", extensionConfig.getValue().initialState());
        assertSame(serDes, extensionConfig.getValue().serDes());

        var stepContext = mock(StepContext.class);
        when(stepContext.getAttempt()).thenReturn(2);
        try (var ignored = BaseContextImpl.attachCurrentContext(stepContext)) {
            var retry = assertInstanceOf(
                    ExtensionStepResult.Retry.class, function.getValue().apply("state"));
            assertEquals("next", retry.state());
            assertEquals(Duration.ofSeconds(7), retry.delay());
        }
    }

    @Test
    void futureTranslatesOnlyFallbackStepFailure() {
        var operation = Operation.builder()
                .id("operation-id")
                .name("ready")
                .type(OperationType.STEP)
                .subType(OperationSubType.WAIT_FOR_CONDITION.getValue())
                .status(OperationStatus.FAILED)
                .stepDetails(StepDetails.builder()
                        .error(ErrorObject.builder().errorMessage("failed").build())
                        .build())
                .build();
        DurableFuture<String> delegate = () -> {
            throw new StepFailedException(operation);
        };

        var future = createFuture(delegate);

        var failure = assertThrows(WaitForConditionFailedException.class, future::get);
        assertSame(operation, failure.getOperation());
    }

    @Test
    void futureDelegatesCompletionSignal() {
        var completion = new CompletableFuture<Void>();
        DurableFuture<String> delegate = new DurableFuture<>() {
            @Override
            public String get() {
                return "done";
            }

            @Override
            public CompletableFuture<Void> completionFuture() {
                return completion;
            }
        };

        assertSame(completion, createFuture(delegate).completionFuture());
    }

    private DurableFuture<String> createFuture(DurableFuture<String> delegate) {
        var context = mock(ExtensionContext.class);
        var reservation = mock(ExtensionOperation.class);
        var resultType = TypeToken.get(String.class);
        when(context.reserve("ready")).thenReturn(reservation);
        when(reservation.stepAsync(
                        eq(OperationSubType.WAIT_FOR_CONDITION.getValue()),
                        eq(resultType),
                        any(ExtensionStepFunction.class),
                        any(ExtensionStepConfig.class)))
                .thenReturn(delegate);
        return DurableWaitForConditionOperation.waitForConditionAsync(
                context,
                "ready",
                resultType,
                (state, step) -> WaitForConditionResult.stopPolling(state),
                WaitForConditionConfig.<String>builder().build().toOperationConfig());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<ExtensionStepFunction<String>> extensionFunction() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(ExtensionStepFunction.class);
    }

    @SuppressWarnings("unchecked")
    private DurableFuture<String> mockStringFuture() {
        var future = (DurableFuture<String>) mock(DurableFuture.class);
        when(future.get()).thenReturn("done");
        return future;
    }
}
