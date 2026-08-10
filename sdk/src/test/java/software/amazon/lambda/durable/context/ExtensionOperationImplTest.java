// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.DurableCallbackFuture;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.ExtensionContextResult;
import software.amazon.lambda.durable.ExtensionOperation;
import software.amazon.lambda.durable.ExtensionStepResult;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.ExtensionContextConfig;
import software.amazon.lambda.durable.config.ExtensionStepConfig;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.StepConfig;

class ExtensionOperationImplTest {
    @Test
    void reservationsKeepSequentialIdsWhenExecutedOutOfOrder() {
        var context = mock(DurableContextImpl.class);
        when(context.reserveOperationId()).thenReturn("sequential-1", "sequential-2");
        doCallRealMethod().when(context).reserve("first");
        doCallRealMethod().when(context).reserve("second");
        var duration = Duration.ofSeconds(1);
        when(context.waitAsyncWithId("sequential-1", "first", duration)).thenReturn(mockFuture());
        when(context.waitAsyncWithId("sequential-2", "second", duration)).thenReturn(mockFuture());

        var first = context.reserve("first");
        var second = context.reserve("second");
        second.waitAsync(duration);
        first.waitAsync(duration);

        var ordered = inOrder(context);
        ordered.verify(context).reserveOperationId();
        ordered.verify(context).reserveOperationId();
        ordered.verify(context).waitAsyncWithId("sequential-2", "second", duration);
        ordered.verify(context).waitAsyncWithId("sequential-1", "first", duration);
    }

    @Test
    void customReservationUsesExplicitLocalOperationId() {
        var context = mock(DurableContextImpl.class);
        var duration = Duration.ofSeconds(1);
        var expectedFuture = mockFuture();
        when(context.reserveOperationId("node-a")).thenReturn("custom-node-a");
        doCallRealMethod().when(context).reserve("custom", "node-a");
        when(context.waitAsyncWithId("custom-node-a", "custom", duration)).thenReturn(expectedFuture);

        var operation = context.reserve("custom", "node-a");
        var actualFuture = operation.waitAsync(duration);

        verify(context).reserveOperationId("node-a");
        verify(context).waitAsyncWithId("custom-node-a", "custom", duration);
        assertEquals(expectedFuture, actualFuture);
    }

    @Test
    void reservedStepAdaptsSupplierToStepFunction() {
        var context = mock(DurableContextImpl.class);
        var future = mockStringFuture();
        var resultType = TypeToken.get(String.class);
        var config = StepConfig.builder().build();
        var called = new AtomicBoolean();
        when(context.stepAsyncWithId(eq("1"), eq("step"), eq(resultType), any(), eq(config)))
                .thenReturn(future);
        var operation = new ExtensionOperationImpl(context, "1", "step");

        assertEquals(
                future,
                operation.stepAsync(
                        resultType,
                        () -> {
                            called.set(true);
                            return "result";
                        },
                        config));

        @SuppressWarnings("unchecked")
        var function = (ArgumentCaptor<Function<StepContext, String>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(Function.class);
        verify(context).stepAsyncWithId(eq("1"), eq("step"), eq(resultType), function.capture(), eq(config));
        assertEquals("result", function.getValue().apply(mock(StepContext.class)));
        assertEquals(true, called.get());
    }

    @Test
    void customSubtypeStepDelegatesExactSubtype() {
        var context = mock(DurableContextImpl.class);
        var future = mockStringFuture();
        var resultType = TypeToken.get(String.class);
        var config = StepConfig.builder().build();
        when(context.stepAsyncWithId(eq("1"), eq("step"), eq("AcmeStep"), eq(resultType), any(), eq(config)))
                .thenReturn(future);

        var actual = new ExtensionOperationImpl(context, "1", "step")
                .stepAsync("AcmeStep", resultType, () -> "result", config);

        assertEquals(future, actual);
    }

    @Test
    void statefulStepDelegatesWithoutExposingStepContext() {
        var context = mock(DurableContextImpl.class);
        var future = mockStringFuture();
        var resultType = TypeToken.get(String.class);
        var config =
                ExtensionStepConfig.<String>builder().initialState("initial").build();
        when(context.extensionStepAsyncWithId(
                        eq("1"), eq("step"), eq("AcmeStateful"), eq(resultType), any(), eq(config)))
                .thenReturn(future);

        var actual = new ExtensionOperationImpl(context, "1", "step")
                .stepAsync("AcmeStateful", resultType, state -> ExtensionStepResult.succeed(state + "-done"), config);

        assertEquals(future, actual);
    }

    @Test
    void reservationDelegatesWaitInvokeAndCallback() {
        var duration = Duration.ofSeconds(2);
        var waitContext = mock(DurableContextImpl.class);
        var waitFuture = mockFuture();
        when(waitContext.waitAsyncWithId("1", "wait", duration)).thenReturn(waitFuture);
        assertEquals(waitFuture, new ExtensionOperationImpl(waitContext, "1", "wait").waitAsync(duration));

        var invokeContext = mock(DurableContextImpl.class);
        var invokeFuture = mockStringFuture();
        var invokeConfig = InvokeConfig.builder().build();
        var resultType = TypeToken.get(String.class);
        when(invokeContext.invokeAsyncWithId("2", "invoke", "target", "payload", resultType, invokeConfig))
                .thenReturn(invokeFuture);
        assertEquals(
                invokeFuture,
                new ExtensionOperationImpl(invokeContext, "2", "invoke")
                        .invokeAsync("target", "payload", resultType, invokeConfig));

        var callbackContext = mock(DurableContextImpl.class);
        @SuppressWarnings("unchecked")
        var callbackFuture = (DurableCallbackFuture<String>) mock(DurableCallbackFuture.class);
        var callbackConfig = CallbackConfig.builder().build();
        when(callbackContext.createCallbackWithId("3", "callback", resultType, callbackConfig))
                .thenReturn(callbackFuture);
        assertEquals(
                callbackFuture,
                new ExtensionOperationImpl(callbackContext, "3", "callback")
                        .createCallback(resultType, callbackConfig));
    }

    @Test
    void customSubtypeSelectorsDelegateExactSubtype() {
        var duration = Duration.ofSeconds(2);
        var resultType = TypeToken.get(String.class);

        var waitContext = mock(DurableContextImpl.class);
        var waitFuture = mockFuture();
        when(waitContext.waitAsyncWithId("1", "wait", "AcmeWait", duration)).thenReturn(waitFuture);
        assertEquals(waitFuture, new ExtensionOperationImpl(waitContext, "1", "wait").waitAsync("AcmeWait", duration));

        var invokeContext = mock(DurableContextImpl.class);
        var invokeFuture = mockStringFuture();
        var invokeConfig = InvokeConfig.builder().build();
        when(invokeContext.invokeAsyncWithId(
                        "2", "invoke", "AcmeInvoke", "target", "payload", resultType, invokeConfig))
                .thenReturn(invokeFuture);
        assertEquals(
                invokeFuture,
                new ExtensionOperationImpl(invokeContext, "2", "invoke")
                        .invokeAsync("AcmeInvoke", "target", "payload", resultType, invokeConfig));

        var callbackContext = mock(DurableContextImpl.class);
        @SuppressWarnings("unchecked")
        var callbackFuture = (DurableCallbackFuture<String>) mock(DurableCallbackFuture.class);
        var callbackConfig = CallbackConfig.builder().build();
        when(callbackContext.createCallbackWithId("3", "callback", "AcmeCallback", resultType, callbackConfig))
                .thenReturn(callbackFuture);
        assertEquals(
                callbackFuture,
                new ExtensionOperationImpl(callbackContext, "3", "callback")
                        .createCallback("AcmeCallback", resultType, callbackConfig));

        var childContext = mock(DurableContextImpl.class);
        var childFuture = mockStringFuture();
        var childConfig = RunInChildContextConfig.builder().build();
        when(childContext.runInChildContextAsyncWithId(
                        eq("4"), eq("child"), eq("AcmeContext"), eq(resultType), any(), eq(childConfig)))
                .thenReturn(childFuture);
        assertEquals(
                childFuture,
                new ExtensionOperationImpl(childContext, "4", "child")
                        .runInChildContextAsync("AcmeContext", resultType, () -> "result", childConfig));
    }

    @Test
    void invalidSubtypeDoesNotClaimReservation() {
        var context = mock(DurableContextImpl.class);
        var duration = Duration.ofSeconds(1);
        var future = mockFuture();
        when(context.waitAsyncWithId("1", "wait", duration)).thenReturn(future);
        var operation = new ExtensionOperationImpl(context, "1", "wait");

        assertThrows(NullPointerException.class, () -> operation.waitAsync(null, duration));
        assertThrows(IllegalArgumentException.class, () -> operation.waitAsync(" ", duration));
        assertEquals(future, operation.waitAsync(duration));
    }

    @Test
    void reservedChildContextAdaptsSupplierToChildFunction() {
        var context = mock(DurableContextImpl.class);
        var future = mockStringFuture();
        var resultType = TypeToken.get(String.class);
        var config = RunInChildContextConfig.builder().build();
        when(context.runInChildContextAsyncWithId(eq("1"), eq("child"), eq(resultType), any(), eq(config)))
                .thenReturn(future);
        var operation = new ExtensionOperationImpl(context, "1", "child");

        assertEquals(future, operation.runInChildContextAsync(resultType, () -> "result", config));

        @SuppressWarnings("unchecked")
        var function = (ArgumentCaptor<Function<DurableContext, String>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(Function.class);
        verify(context)
                .runInChildContextAsyncWithId(eq("1"), eq("child"), eq(resultType), function.capture(), eq(config));
        assertEquals("result", function.getValue().apply(mock(DurableContext.class)));
    }

    @Test
    void advancedChildContextDelegatesFrameworkFunction() {
        var context = mock(DurableContextImpl.class);
        var future = mockStringFuture();
        var resultType = TypeToken.get(String.class);
        var config = ExtensionContextConfig.builder().build();
        when(context.extensionContextAsyncWithId(
                        eq("1"), eq("child"), eq("AcmeContext"), eq(resultType), any(), eq(config)))
                .thenReturn(future);

        var actual = new ExtensionOperationImpl(context, "1", "child")
                .runInChildContextAsync(
                        "AcmeContext", resultType, () -> ExtensionContextResult.completed("result"), config);

        assertEquals(future, actual);
    }

    @Test
    void reservationCanOnlyExecuteOnceAcrossPrimitiveSelectors() {
        var context = mock(DurableContextImpl.class);
        var duration = Duration.ofSeconds(1);
        when(context.waitAsyncWithId("1", "only-once", duration)).thenReturn(mockFuture());
        ExtensionOperation operation = new ExtensionOperationImpl(context, "1", "only-once");

        operation.waitAsync(duration);

        assertThrows(IllegalStateException.class, () -> operation.stepAsync(String.class, () -> "second"));
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
