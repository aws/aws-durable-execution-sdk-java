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

import java.time.Duration;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.context.BaseContextImpl;

class DurableCoreOperationsTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void stepAcceptsContextFreeSupplier() {
        var context = mock(DurableContext.class);
        BaseContextImpl.setCurrentContext(context);

        DurableCoreOperations.step("step", String.class, () -> "result");

        @SuppressWarnings("unchecked")
        var function = (ArgumentCaptor<Function<StepContext, String>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(Function.class);
        verify(context).step(eq("step"), eq(String.class), function.capture());
        assertEquals("result", function.getValue().apply(mock(StepContext.class)));
    }

    @Test
    void childContextAcceptsContextFreeSupplier() {
        var context = mock(DurableContext.class);
        BaseContextImpl.setCurrentContext(context);

        DurableCoreOperations.runInChildContext("child", String.class, () -> "result");

        @SuppressWarnings("unchecked")
        var function = (ArgumentCaptor<Function<DurableContext, String>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(Function.class);
        verify(context).runInChildContext(eq("child"), eq(String.class), function.capture());
        assertEquals("result", function.getValue().apply(mock(DurableContext.class)));
    }

    @Test
    void coreValueOperationsDelegateToCurrentContext() {
        var context = mock(DurableContext.class);
        BaseContextImpl.setCurrentContext(context);
        var duration = Duration.ofSeconds(1);
        var waitFuture = mockFuture();
        var invokeFuture = mockStringFuture();
        @SuppressWarnings("unchecked")
        var callbackFuture = (DurableCallbackFuture<String>) mock(DurableCallbackFuture.class);
        when(context.waitAsync("wait", duration)).thenReturn(waitFuture);
        when(context.invokeAsync("invoke", "function", "payload", String.class)).thenReturn(invokeFuture);
        when(context.createCallback("callback", String.class)).thenReturn(callbackFuture);

        assertEquals(waitFuture, DurableCoreOperations.waitAsync("wait", duration));
        assertEquals(invokeFuture, DurableCoreOperations.invokeAsync("invoke", "function", "payload", String.class));
        assertEquals(callbackFuture, DurableCoreOperations.createCallback("callback", String.class));
    }

    @Test
    void configuredSupplierOverloadsDelegateToCurrentContext() {
        var context = mock(DurableContext.class);
        BaseContextImpl.setCurrentContext(context);
        var stepConfig = StepConfig.builder().build();
        var childConfig = RunInChildContextConfig.builder().build();

        DurableCoreOperations.stepAsync("step", new TypeToken<String>() {}, () -> "step", stepConfig);
        DurableCoreOperations.runInChildContextAsync("child", new TypeToken<String>() {}, () -> "child", childConfig);

        verify(context).stepAsync(eq("step"), any(TypeToken.class), any(Function.class), eq(stepConfig));
        verify(context).runInChildContextAsync(eq("child"), any(TypeToken.class), any(Function.class), eq(childConfig));
    }

    @Test
    void coreOperationsFailOutsideDurableContext() {
        assertThrows(
                IllegalStateException.class, () -> DurableCoreOperations.step("step", String.class, () -> "result"));
    }

    @SuppressWarnings("unchecked")
    private DurableFuture<Void> mockFuture() {
        return mock(DurableFuture.class);
    }

    @SuppressWarnings("unchecked")
    private DurableFuture<String> mockStringFuture() {
        return mock(DurableFuture.class);
    }
}
