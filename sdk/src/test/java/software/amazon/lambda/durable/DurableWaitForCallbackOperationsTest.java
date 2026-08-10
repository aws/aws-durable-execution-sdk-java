// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.context.BaseContextImpl;

class DurableWaitForCallbackOperationsTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void callbackSubmitterUsesRunnableAndScopedCallbackId() {
        var context = mock(DurableContext.class);
        BaseContextImpl.setCurrentContext(context);

        DurableWaitForCallbackOperations.waitForCallback(
                "callback",
                String.class,
                () -> assertEquals(
                        "callback-id",
                        WaitForCallbackContext.getCurrentContext().getCallbackId()));

        @SuppressWarnings("unchecked")
        var submitter = (ArgumentCaptor<BiConsumer<String, StepContext>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(BiConsumer.class);
        verify(context).waitForCallback(eq("callback"), eq(String.class), submitter.capture());
        submitter.getValue().accept("callback-id", mock(StepContext.class));
        assertThrows(IllegalStateException.class, WaitForCallbackContext::getCurrentContext);
    }
}
