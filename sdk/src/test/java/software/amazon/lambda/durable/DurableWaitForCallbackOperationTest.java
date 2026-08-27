// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.extension.ExtensionCallback;
import software.amazon.lambda.durable.extension.ExtensionCallbackConfig;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextFunction;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepFunction;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.operation.DurableWaitForCallbackOperation;
import software.amazon.lambda.durable.operation.DurableWaitForCallbackOperation.WaitForCallbackContext;

class DurableWaitForCallbackOperationTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void callbackSubmitterUsesRunnableAndScopedCallbackId() {
        var context = mock(ExtensionContext.class);
        var parent = mock(ExtensionOperation.class);
        var parentFuture = CompletableFuture.completedFuture("approved");
        when(context.reserve("callback")).thenReturn(parent);
        when(parent.runInChildContextAsync(
                        eq(OperationSubType.WAIT_FOR_CALLBACK.getValue()),
                        eq(TypeToken.get(String.class)),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(parentFuture);
        BaseContextImpl.setCurrentContext(context);

        assertEquals(
                "approved",
                DurableWaitForCallbackOperation.waitForCallback(
                        "callback",
                        String.class,
                        () -> assertEquals(
                                "callback-id",
                                WaitForCallbackContext.getCurrentContext().getCallbackId())));

        @SuppressWarnings("unchecked")
        var function = (ArgumentCaptor<ExtensionContextFunction<String>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(ExtensionContextFunction.class);
        verify(parent)
                .runInChildContextAsync(
                        eq(OperationSubType.WAIT_FOR_CALLBACK.getValue()),
                        eq(TypeToken.get(String.class)),
                        function.capture(),
                        any(ExtensionContextConfig.class));

        var child = mock(CurrentContext.class);
        var callbackReservation = mock(ExtensionOperation.class);
        var submitterReservation = mock(ExtensionOperation.class);
        @SuppressWarnings("unchecked")
        var callback = (ExtensionCallback<String>) mock(ExtensionCallback.class);
        var submitterFuture = CompletableFuture.<Void>completedFuture(null);
        when(child.reserve("callback-callback")).thenReturn(callbackReservation);
        when(child.reserve("callback-submitter")).thenReturn(submitterReservation);
        when(callbackReservation.createCallback(
                        eq(OperationSubType.CALLBACK.getValue()),
                        eq(TypeToken.get(String.class)),
                        any(ExtensionCallbackConfig.class)))
                .thenReturn(callback);
        when(submitterReservation.stepAsync(
                        eq(OperationSubType.STEP.getValue()),
                        eq(TypeToken.get(Void.class)),
                        any(ExtensionStepFunction.class),
                        any(ExtensionStepConfig.class)))
                .thenReturn(submitterFuture);
        when(callback.callbackId()).thenReturn("callback-id");
        when(callback.result()).thenReturn(CompletableFuture.completedFuture("approved"));

        try (var ignored = BaseContextImpl.attachCurrentContext(child)) {
            assertEquals(
                    "approved",
                    function.getValue().apply().toCompletableFuture().join().result());
        }

        @SuppressWarnings("unchecked")
        var submitter = (ArgumentCaptor<ExtensionStepFunction<Void>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(ExtensionStepFunction.class);
        verify(submitterReservation)
                .stepAsync(
                        eq(OperationSubType.STEP.getValue()),
                        eq(TypeToken.get(Void.class)),
                        submitter.capture(),
                        any(ExtensionStepConfig.class));
        try (var ignored = BaseContextImpl.attachCurrentContext(mock(StepContext.class))) {
            submitter.getValue().apply(null).toCompletableFuture().join();
        }
        assertThrows(IllegalStateException.class, WaitForCallbackContext::getCurrentContext);
    }

    private interface CurrentContext extends DurableContext, ExtensionContext {}
}
