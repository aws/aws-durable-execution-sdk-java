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

import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextFunction;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.model.OperationSubType;

class DurableWaitForCallbackOperationsTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void callbackSubmitterUsesRunnableAndScopedCallbackId() {
        var context = mock(ExtensionContext.class);
        var parent = mock(ExtensionOperation.class);
        var parentFuture = mockStringFuture();
        when(context.reserve("callback")).thenReturn(parent);
        when(parentFuture.get()).thenReturn("approved");
        when(parent.runInChildContextAsync(
                        eq(OperationSubType.WAIT_FOR_CALLBACK.getValue()),
                        eq(TypeToken.get(String.class)),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(parentFuture);
        BaseContextImpl.setCurrentContext(context);

        assertEquals(
                "approved",
                DurableWaitForCallbackOperations.waitForCallback(
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

        var child = mock(ExtensionContext.class);
        var callbackReservation = mock(ExtensionOperation.class);
        var submitterReservation = mock(ExtensionOperation.class);
        @SuppressWarnings("unchecked")
        var callback = (DurableCallbackFuture<String>) mock(DurableCallbackFuture.class);
        when(child.reserve("callback-callback")).thenReturn(callbackReservation);
        when(child.reserve("callback-submitter")).thenReturn(submitterReservation);
        when(callbackReservation.createCallback(eq(TypeToken.get(String.class)), any(CallbackConfig.class)))
                .thenReturn(callback);
        when(callback.callbackId()).thenReturn("callback-id");
        when(callback.get()).thenReturn("approved");

        try (var ignored = BaseContextImpl.attachCurrentContext(child)) {
            assertEquals("approved", function.getValue().apply().result());
        }

        @SuppressWarnings("unchecked")
        var submitter = (ArgumentCaptor<Supplier<Void>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Supplier.class);
        verify(submitterReservation).step(eq(Void.class), submitter.capture(), any(StepConfig.class));
        try (var ignored = BaseContextImpl.attachCurrentContext(mock(StepContext.class))) {
            submitter.getValue().get();
        }
        assertThrows(IllegalStateException.class, WaitForCallbackContext::getCurrentContext);
    }

    @SuppressWarnings("unchecked")
    private DurableFuture<String> mockStringFuture() {
        return mock(DurableFuture.class);
    }
}
