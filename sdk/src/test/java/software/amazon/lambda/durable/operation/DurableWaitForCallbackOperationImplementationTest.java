// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.lambda.model.CallbackDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.exception.CallbackTimeoutException;
import software.amazon.lambda.durable.extension.ExtensionCallback;
import software.amazon.lambda.durable.extension.ExtensionCallbackConfig;
import software.amazon.lambda.durable.extension.ExtensionChildOperationSummary;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextFailure;
import software.amazon.lambda.durable.extension.ExtensionContextFunction;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepFunction;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class DurableWaitForCallbackOperationImplementationTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void executeCreatesExistingWaitForCallbackContextTopology() {
        var context = mock(ExtensionContext.class);
        var parent = mock(ExtensionOperation.class);
        var future = mockStringFuture();
        var resultType = TypeToken.get(String.class);
        var serDes = new JacksonSerDes();
        var config = WaitForCallbackConfig.builder()
                .stepConfig(StepConfig.builder().serDes(serDes).build())
                .build();
        when(context.reserve("approval")).thenReturn(parent);
        when(parent.runInChildContextAsync(
                        eq(OperationSubType.WAIT_FOR_CALLBACK.getValue()),
                        eq(resultType),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(future);

        var actual = DurableWaitForCallbackOperation.waitForCallbackAsync(
                context, "approval", resultType, (callbackId, stepContext) -> {}, config.toOperationConfig());

        assertEquals("result", actual.get());
        var contextConfig = ArgumentCaptor.forClass(ExtensionContextConfig.class);
        verify(parent)
                .runInChildContextAsync(
                        eq(OperationSubType.WAIT_FOR_CALLBACK.getValue()),
                        eq(resultType),
                        any(ExtensionContextFunction.class),
                        contextConfig.capture());
        assertSame(serDes, contextConfig.getValue().serDes());
    }

    @Test
    void largeResultReplaysCallbackChildren() {
        var context = mock(ExtensionContext.class);
        var parent = mock(ExtensionOperation.class);
        var resultType = TypeToken.get(String.class);
        when(context.reserve("approval")).thenReturn(parent);
        when(parent.runInChildContextAsync(
                        eq(OperationSubType.WAIT_FOR_CALLBACK.getValue()),
                        eq(resultType),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(mockStringFuture());

        DurableWaitForCallbackOperation.waitForCallbackAsync(
                context,
                "approval",
                resultType,
                (callbackId, stepContext) -> {},
                WaitForCallbackConfig.builder().build().toOperationConfig());

        var function = extensionFunction();
        verify(parent)
                .runInChildContextAsync(
                        eq(OperationSubType.WAIT_FOR_CALLBACK.getValue()),
                        eq(resultType),
                        function.capture(),
                        any(ExtensionContextConfig.class));
        var child = mock(CurrentExtensionContext.class);
        var callbackReservation = mock(ExtensionOperation.class);
        var submitterReservation = mock(ExtensionOperation.class);
        var callback = mockStringCallback();
        var submitter = mockVoidFuture();
        var largeResult = "x".repeat(256 * 1024);
        when(child.reserve("approval-callback")).thenReturn(callbackReservation);
        when(child.reserve("approval-submitter")).thenReturn(submitterReservation);
        when(callbackReservation.createCallback(
                        eq(OperationSubType.CALLBACK.getValue()), eq(resultType), any(ExtensionCallbackConfig.class)))
                .thenReturn(callback);
        when(callback.callbackId()).thenReturn("callback-id");
        when(callback.result()).thenReturn(CompletableFuture.completedFuture(largeResult));
        when(submitterReservation.stepAsync(
                        eq(OperationSubType.STEP.getValue()),
                        eq(TypeToken.get(Void.class)),
                        any(ExtensionStepFunction.class),
                        any(ExtensionStepConfig.class)))
                .thenReturn(submitter);
        BaseContextImpl.setCurrentContext(child);

        var result = function.getValue().apply().toCompletableFuture().join();

        assertSame(largeResult, result.result());
        assertFalse(result.shouldReplayChildren(256 * 1024 - 1));
        assertTrue(result.shouldReplayChildren(256 * 1024));
    }

    @Test
    void errorHandlerPreservesCallbackTimeoutException() {
        var context = mock(ExtensionContext.class);
        var parent = mock(ExtensionOperation.class);
        var resultType = TypeToken.get(String.class);
        when(context.reserve("approval")).thenReturn(parent);
        when(parent.runInChildContextAsync(
                        eq(OperationSubType.WAIT_FOR_CALLBACK.getValue()),
                        eq(resultType),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(mockStringFuture());
        DurableWaitForCallbackOperation.waitForCallbackAsync(
                context,
                "approval",
                resultType,
                (String callbackId, StepContext stepContext) -> {},
                WaitForCallbackConfig.builder().build().toOperationConfig());
        var config = ArgumentCaptor.forClass(ExtensionContextConfig.class);
        verify(parent)
                .runInChildContextAsync(
                        eq(OperationSubType.WAIT_FOR_CALLBACK.getValue()),
                        eq(resultType),
                        any(ExtensionContextFunction.class),
                        config.capture());
        var callback = Operation.builder()
                .id("callback-op")
                .name("approval-callback")
                .type(OperationType.CALLBACK)
                .subType(OperationSubType.CALLBACK.getValue())
                .status(OperationStatus.TIMED_OUT)
                .callbackDetails(
                        CallbackDetails.builder().callbackId("callback-id").build())
                .build();
        var failure = new ExtensionContextFailure(
                Operation.builder()
                        .id("approval")
                        .name("approval")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.WAIT_FOR_CALLBACK.getValue())
                        .status(OperationStatus.FAILED)
                        .build(),
                null,
                List.of(new ExtensionChildOperationSummary(callback)));

        var translated = config.getValue().errorHandler().translate(failure);

        var timeout = assertInstanceOf(CallbackTimeoutException.class, translated);
        assertEquals("callback-id", timeout.getCallbackId());
    }

    private CompletableFuture<String> mockStringFuture() {
        return CompletableFuture.completedFuture("result");
    }

    @SuppressWarnings("unchecked")
    private ExtensionCallback<String> mockStringCallback() {
        return mock(ExtensionCallback.class);
    }

    private CompletableFuture<Void> mockVoidFuture() {
        return CompletableFuture.completedFuture(null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<ExtensionContextFunction<String>> extensionFunction() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(ExtensionContextFunction.class);
    }

    private interface CurrentExtensionContext extends DurableContext, ExtensionContext {}
}
