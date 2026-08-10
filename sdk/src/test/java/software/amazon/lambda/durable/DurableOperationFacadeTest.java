// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static software.amazon.lambda.durable.model.OperationSubType.CALLBACK;
import static software.amazon.lambda.durable.model.OperationSubType.CHAINED_INVOKE;
import static software.amazon.lambda.durable.model.OperationSubType.RUN_IN_CHILD_CONTEXT;
import static software.amazon.lambda.durable.model.OperationSubType.STEP;
import static software.amazon.lambda.durable.model.OperationSubType.WAIT;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.StepSemantics;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.extension.ExtensionCallbackConfig;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextFunction;
import software.amazon.lambda.durable.extension.ExtensionInvokeConfig;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepFunction;
import software.amazon.lambda.durable.extension.ExtensionStepResult;
import software.amazon.lambda.durable.operation.DurableCallbackOperation;
import software.amazon.lambda.durable.operation.DurableContextOperation;
import software.amazon.lambda.durable.operation.DurableInvokeOperation;
import software.amazon.lambda.durable.operation.DurableStepOperation;
import software.amazon.lambda.durable.operation.DurableWaitOperation;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.serde.SerDes;

class DurableOperationFacadeTest {
    private static final String PACKAGE_NAME = "software.amazon.lambda.durable.";

    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void operationFacadesUseSingularClassNames() {
        assertFacadeRenamed("DurableStepOperation", "DurableStepOperations");
        assertFacadeRenamed("DurableWaitOperation", "DurableWaitOperations");
        assertFacadeRenamed("DurableInvokeOperation", "DurableInvokeOperations");
        assertFacadeRenamed("DurableCallbackOperation", "DurableCallbackOperations");
        assertFacadeRenamed("DurableContextOperation", "DurableContextOperations");
        assertFacadeRenamed("DurableMapOperation", "DurableMapOperations");
        assertFacadeRenamed("DurableParallelOperation", "DurableParallelOperations");
        assertFacadeRenamed("DurableWaitForCallbackOperation", "DurableWaitForCallbackOperations");
        assertFacadeRenamed("DurableWaitForConditionOperation", "DurableWaitForConditionOperations");
        assertFacadeRenamed("DurableWithRetryOperation", "DurableWithRetryOperations");
    }

    @Test
    void backendPrimitivesUsePrimitiveClassNames() {
        assertClassMoved("primitive.BasePrimitive", "primitive.BaseDurableOperation");
        assertClassMoved("primitive.SerializablePrimitive", "primitive.SerializableDurableOperation");
        assertClassMoved("primitive.StepPrimitive", "primitive.StepOperation");
        assertClassMoved("primitive.WaitPrimitive", "primitive.WaitOperation");
        assertClassMoved("primitive.InvokePrimitive", "primitive.InvokeOperation");
        assertClassMoved("primitive.CallbackPrimitive", "primitive.CallbackOperation");
        assertClassMoved("primitive.ChildContextPrimitive", "primitive.ChildContextOperation");
    }

    @Test
    void extensionOperationImplementationLivesWithExtensionSpi() {
        assertClassMoved("extension.ExtensionOperationImpl", "context.ExtensionOperationImpl");
    }

    @Test
    void operationFacadesAbsorbTheirExtensions() {
        assertMergedOperation("DurableStepOperation", "StepExtension");
        assertMergedOperation("DurableWaitOperation", "WaitExtension");
        assertMergedOperation("DurableInvokeOperation", "InvokeExtension");
        assertMergedOperation("DurableCallbackOperation", "CallbackExtension");
        assertMergedOperation("DurableContextOperation", "ContextExtension");
        assertMergedOperation("DurableMapOperation", "MapExtension");
        assertMergedOperation("DurableParallelOperation", "ParallelExtension");
        assertMergedOperation("DurableWaitForCallbackOperation", "WaitForCallbackExtension");
        assertMergedOperation("DurableWaitForConditionOperation", "WaitForConditionExtension");
        assertMergedOperation("DurableWithRetryOperation", "WithRetryExtension");
    }

    @Test
    void stepAcceptsContextFreeSupplier() {
        var context = mockDurableContext();
        var reservation = mock(ExtensionOperation.class);
        var future = mockStringFuture();
        BaseContextImpl.setCurrentContext(context);
        when(((ExtensionContext) context).reserve("step")).thenReturn(reservation);
        when(reservation.stepAsync(
                        eq(STEP.getValue()),
                        any(TypeToken.class),
                        any(ExtensionStepFunction.class),
                        any(ExtensionStepConfig.class)))
                .thenReturn(future);

        assertSame(future, DurableStepOperation.stepAsync("step", String.class, () -> "result"));

        @SuppressWarnings("unchecked")
        var function = (ArgumentCaptor<ExtensionStepFunction<String>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(ExtensionStepFunction.class);
        verify(reservation)
                .stepAsync(
                        eq(STEP.getValue()), any(TypeToken.class), function.capture(), any(ExtensionStepConfig.class));
        try (var ignored = BaseContextImpl.attachCurrentContext(mock(StepContext.class))) {
            var result = assertInstanceOf(
                    ExtensionStepResult.Succeeded.class, function.getValue().apply(null));
            assertEquals("result", result.value());
        }
    }

    @Test
    void stepAdaptsUserFacingRetryConfigToExtensionSpi() {
        var context = mock(ExtensionContext.class);
        var reservation = mock(ExtensionOperation.class);
        var future = mockStringFuture();
        var resultType = TypeToken.get(String.class);
        when(context.reserve("step")).thenReturn(reservation);
        when(reservation.stepAsync(
                        eq(STEP.getValue()),
                        eq(resultType),
                        any(ExtensionStepFunction.class),
                        any(ExtensionStepConfig.class)))
                .thenReturn(future);
        var config = DurableStepOperation.StepConfig.builder()
                .retryStrategy((error, attempt) ->
                        attempt == 1 ? RetryDecision.retry(Duration.ofSeconds(3)) : RetryDecision.fail())
                .semanticsPerRetry(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                .build();

        assertSame(future, DurableStepOperation.stepAsync(context, "step", resultType, ignored -> "result", config));

        var extensionConfig = ArgumentCaptor.forClass(ExtensionStepConfig.class);
        verify(reservation)
                .stepAsync(
                        eq(STEP.getValue()),
                        eq(resultType),
                        any(ExtensionStepFunction.class),
                        extensionConfig.capture());
        assertEquals(
                ExtensionStepConfig.StepSemantics.AT_MOST_ONCE_PER_RETRY,
                extensionConfig.getValue().semanticsPerRetry());
        var retry = assertInstanceOf(
                ExtensionStepResult.Retry.class,
                extensionConfig
                        .getValue()
                        .retryStrategy()
                        .makeRetryDecision(new IllegalStateException("retry"), null, 1));
        var doNotRetry = extensionConfig
                .getValue()
                .retryStrategy()
                .makeRetryDecision(new IllegalStateException("fail"), null, 2);
        assertEquals(Duration.ofSeconds(3), retry.delay());
        assertInstanceOf(ExtensionStepResult.DoNotRetry.class, doNotRetry);
    }

    @Test
    void durableContextStepUsesPrimitiveExtension() {
        var future = mockStringFuture();
        var reservation = mock(ExtensionOperation.class);
        var context = mock(
                DurableContext.class,
                withSettings().extraInterfaces(ExtensionContext.class).defaultAnswer(CALLS_REAL_METHODS));
        when(((ExtensionContext) context).reserve("step")).thenReturn(reservation);
        when(reservation.stepAsync(
                        eq(STEP.getValue()),
                        any(TypeToken.class),
                        any(ExtensionStepFunction.class),
                        any(ExtensionStepConfig.class)))
                .thenReturn(future);

        var result = context.stepAsync(
                "step",
                TypeToken.get(String.class),
                ignored -> "result",
                StepConfig.builder().build());

        assertSame(future, result);
    }

    @Test
    void durableContextWaitUsesPrimitiveExtension() {
        var future = mockFuture();
        var reservation = mock(ExtensionOperation.class);
        var context = mockDurableContext();
        var duration = Duration.ofSeconds(1);
        when(((ExtensionContext) context).reserve("wait")).thenReturn(reservation);
        when(reservation.waitAsync(WAIT.getValue(), duration)).thenReturn(future);

        var result = context.waitAsync("wait", duration);

        assertSame(future, result);
    }

    @Test
    void durableContextInvokeUsesPrimitiveExtension() {
        var future = mockStringFuture();
        var reservation = mock(ExtensionOperation.class);
        var context = mockDurableContext();
        var payloadSerDes = mock(SerDes.class);
        var resultSerDes = mock(SerDes.class);
        var config = InvokeConfig.builder()
                .payloadSerDes(payloadSerDes)
                .serDes(resultSerDes)
                .tenantId("tenant")
                .build();
        when(((ExtensionContext) context).reserve("invoke")).thenReturn(reservation);
        when(reservation.invokeAsync(
                        eq(CHAINED_INVOKE.getValue()), eq("function"), eq("payload"), any(TypeToken.class), any()))
                .thenReturn(future);

        var result = context.invokeAsync("invoke", "function", "payload", TypeToken.get(String.class), config);

        assertSame(future, result);
        var extensionConfig = ArgumentCaptor.forClass(ExtensionInvokeConfig.class);
        verify(reservation)
                .invokeAsync(
                        eq(CHAINED_INVOKE.getValue()),
                        eq("function"),
                        eq("payload"),
                        eq(TypeToken.get(String.class)),
                        extensionConfig.capture());
        assertSame(payloadSerDes, extensionConfig.getValue().payloadSerDes());
        assertSame(resultSerDes, extensionConfig.getValue().serDes());
        assertEquals("tenant", extensionConfig.getValue().tenantId());
    }

    @Test
    void durableContextCallbackUsesPrimitiveExtension() {
        @SuppressWarnings("unchecked")
        var future = (DurableCallbackFuture<String>) mock(DurableCallbackFuture.class);
        var reservation = mock(ExtensionOperation.class);
        var context = mockDurableContext();
        var serDes = mock(SerDes.class);
        var config = CallbackConfig.builder()
                .timeout(Duration.ofMinutes(5))
                .heartbeatTimeout(Duration.ofMinutes(1))
                .serDes(serDes)
                .build();
        when(((ExtensionContext) context).reserve("callback")).thenReturn(reservation);
        when(reservation.createCallback(eq(CALLBACK.getValue()), any(TypeToken.class), any()))
                .thenReturn(future);

        var result = context.createCallback("callback", TypeToken.get(String.class), config);

        assertSame(future, result);
        var extensionConfig = ArgumentCaptor.forClass(ExtensionCallbackConfig.class);
        verify(reservation)
                .createCallback(eq(CALLBACK.getValue()), eq(TypeToken.get(String.class)), extensionConfig.capture());
        assertEquals(Duration.ofMinutes(5), extensionConfig.getValue().timeout());
        assertEquals(Duration.ofMinutes(1), extensionConfig.getValue().heartbeatTimeout());
        assertSame(serDes, extensionConfig.getValue().serDes());
    }

    @Test
    void durableContextChildContextUsesPrimitiveExtension() {
        var future = mockStringFuture();
        var reservation = mock(ExtensionOperation.class);
        var context = mockDurableContext();
        var serDes = mock(SerDes.class);
        when(((ExtensionContext) context).reserve("child")).thenReturn(reservation);
        when(reservation.runInChildContextAsync(
                        eq(RUN_IN_CHILD_CONTEXT.getValue()),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(future);

        var result = context.runInChildContextAsync(
                "child",
                TypeToken.get(String.class),
                ignored -> "result",
                RunInChildContextConfig.builder().serDes(serDes).isVirtual(true).build());

        assertSame(future, result);
        var extensionConfig = ArgumentCaptor.forClass(ExtensionContextConfig.class);
        verify(reservation)
                .runInChildContextAsync(
                        eq(RUN_IN_CHILD_CONTEXT.getValue()),
                        eq(TypeToken.get(String.class)),
                        any(ExtensionContextFunction.class),
                        extensionConfig.capture());
        assertSame(serDes, extensionConfig.getValue().serDes());
        assertTrue(extensionConfig.getValue().isVirtual());
    }

    @Test
    void childContextAcceptsContextFreeSupplier() {
        var context = mockDurableContext();
        var reservation = mock(ExtensionOperation.class);
        var future = mockStringFuture();
        BaseContextImpl.setCurrentContext(context);
        when(((ExtensionContext) context).reserve("child")).thenReturn(reservation);
        when(reservation.runInChildContextAsync(
                        eq(RUN_IN_CHILD_CONTEXT.getValue()),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(future);

        assertSame(future, DurableContextOperation.runInChildContextAsync("child", String.class, () -> "result"));

        @SuppressWarnings("unchecked")
        var function = (ArgumentCaptor<ExtensionContextFunction<String>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(ExtensionContextFunction.class);
        verify(reservation)
                .runInChildContextAsync(
                        eq(RUN_IN_CHILD_CONTEXT.getValue()),
                        any(TypeToken.class),
                        function.capture(),
                        any(ExtensionContextConfig.class));
        try (var ignored = BaseContextImpl.attachCurrentContext(context)) {
            assertEquals("result", function.getValue().apply().result());
        }
    }

    @Test
    void valueOperationsDelegateToCurrentContext() {
        var context = mockDurableContext();
        BaseContextImpl.setCurrentContext(context);
        var duration = Duration.ofSeconds(1);
        var waitFuture = mockFuture();
        var invokeFuture = mockStringFuture();
        @SuppressWarnings("unchecked")
        var callbackFuture = (DurableCallbackFuture<String>) mock(DurableCallbackFuture.class);
        var waitReservation = mock(ExtensionOperation.class);
        var invokeReservation = mock(ExtensionOperation.class);
        var callbackReservation = mock(ExtensionOperation.class);
        when(((ExtensionContext) context).reserve("wait")).thenReturn(waitReservation);
        when(((ExtensionContext) context).reserve("invoke")).thenReturn(invokeReservation);
        when(((ExtensionContext) context).reserve("callback")).thenReturn(callbackReservation);
        when(waitReservation.waitAsync(WAIT.getValue(), duration)).thenReturn(waitFuture);
        when(invokeReservation.invokeAsync(
                        eq(CHAINED_INVOKE.getValue()), eq("function"), eq("payload"), any(TypeToken.class), any()))
                .thenReturn(invokeFuture);
        when(callbackReservation.createCallback(eq(CALLBACK.getValue()), any(TypeToken.class), any()))
                .thenReturn(callbackFuture);

        assertEquals(waitFuture, DurableWaitOperation.waitAsync("wait", duration));
        assertEquals(invokeFuture, DurableInvokeOperation.invokeAsync("invoke", "function", "payload", String.class));
        assertEquals(callbackFuture, DurableCallbackOperation.createCallback("callback", String.class));
    }

    @Test
    void configuredSupplierOverloadsDelegateToCurrentContext() {
        var context = mockDurableContext();
        var stepReservation = mock(ExtensionOperation.class);
        var childReservation = mock(ExtensionOperation.class);
        BaseContextImpl.setCurrentContext(context);
        var stepConfig = StepConfig.builder().build();
        var childConfig = RunInChildContextConfig.builder().build();
        when(((ExtensionContext) context).reserve("step")).thenReturn(stepReservation);
        when(((ExtensionContext) context).reserve("child")).thenReturn(childReservation);

        DurableStepOperation.stepAsync(
                "step", new TypeToken<String>() {}, () -> "step", stepConfig.toOperationConfig());
        DurableContextOperation.runInChildContextAsync(
                "child", new TypeToken<String>() {}, () -> "child", childConfig.toOperationConfig());

        verify(stepReservation)
                .stepAsync(
                        eq(STEP.getValue()),
                        any(TypeToken.class),
                        any(ExtensionStepFunction.class),
                        any(ExtensionStepConfig.class));
        verify(childReservation)
                .runInChildContextAsync(
                        eq(RUN_IN_CHILD_CONTEXT.getValue()),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class));
    }

    @Test
    void primitiveOperationsFailOutsideDurableContext() {
        assertThrows(
                IllegalStateException.class, () -> DurableStepOperation.step("step", String.class, () -> "result"));
    }

    @SuppressWarnings("unchecked")
    private DurableFuture<Void> mockFuture() {
        return mock(DurableFuture.class);
    }

    @SuppressWarnings("unchecked")
    private DurableFuture<String> mockStringFuture() {
        return mock(DurableFuture.class);
    }

    private DurableContext mockDurableContext() {
        return mock(
                DurableContext.class,
                withSettings().extraInterfaces(ExtensionContext.class).defaultAnswer(CALLS_REAL_METHODS));
    }

    private void assertFacadeRenamed(String singularName, String pluralName) {
        assertTrue(classExists("operation." + singularName), singularName + " must be part of the public API");
        assertFalse(classExists(singularName), singularName + " must be removed from the root package");
        assertFalse(classExists(pluralName), pluralName + " must be removed from the public API");
        assertFalse(classExists("operation." + pluralName), pluralName + " must be removed from the operation package");
    }

    private void assertClassMoved(String newName, String oldName) {
        assertTrue(classExists(newName), newName + " must exist");
        assertFalse(classExists(oldName), oldName + " must be removed");
    }

    private void assertMergedOperation(String operationName, String extensionName) {
        assertClassMoved("operation." + operationName, operationName);
        assertFalse(classExists("operation." + extensionName), extensionName + " must be merged into " + operationName);
    }

    private boolean classExists(String simpleName) {
        try {
            Class.forName(PACKAGE_NAME + simpleName);
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}
