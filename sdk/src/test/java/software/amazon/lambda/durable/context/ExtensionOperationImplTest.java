// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.CallbackDetails;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.extension.ExtensionCallbackConfig;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextResult;
import software.amazon.lambda.durable.extension.ExtensionInvokeConfig;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.extension.ExtensionOperationImpl;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepResult;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.primitive.BasePrimitive;
import software.amazon.lambda.durable.primitive.CallbackPrimitive;
import software.amazon.lambda.durable.primitive.ChildContextPrimitive;
import software.amazon.lambda.durable.primitive.InvokePrimitive;
import software.amazon.lambda.durable.primitive.StepPrimitive;
import software.amazon.lambda.durable.primitive.WaitPrimitive;

class ExtensionOperationImplTest {
    @Test
    void reservationsKeepSequentialIdsWhenExecutedOutOfOrder() {
        var context = context();
        when(context.reserveOperationId()).thenReturn("sequential-1", "sequential-2");
        doCallRealMethod().when(context).reserve("first");
        doCallRealMethod().when(context).reserve("second");
        replay(context, "sequential-1", "first", OperationType.WAIT, OperationSubType.WAIT.getValue());
        replay(context, "sequential-2", "second", OperationType.WAIT, OperationSubType.WAIT.getValue());

        var first = context.reserve("first");
        var second = context.reserve("second");
        var secondFuture = second.waitAsync(OperationSubType.WAIT.getValue(), Duration.ofSeconds(1));
        var firstFuture = first.waitAsync(OperationSubType.WAIT.getValue(), Duration.ofSeconds(1));

        assertEquals(
                "sequential-2",
                assertInstanceOf(BasePrimitive.class, secondFuture).getOperationId());
        assertEquals(
                "sequential-1",
                assertInstanceOf(BasePrimitive.class, firstFuture).getOperationId());
    }

    @Test
    void customReservationUsesExplicitLocalOperationId() {
        var context = context();
        when(context.reserveOperationId("node-a")).thenReturn("custom-node-a");
        doCallRealMethod().when(context).reserve("custom", "node-a");
        replay(context, "custom-node-a", "custom", OperationType.WAIT, OperationSubType.WAIT.getValue());

        var future =
                context.reserve("custom", "node-a").waitAsync(OperationSubType.WAIT.getValue(), Duration.ofSeconds(1));

        assertEquals(
                "custom-node-a", assertInstanceOf(BasePrimitive.class, future).getOperationId());
    }

    @Test
    void statefulStepCreatesStepOperationWithExactSubtype() {
        var context = context();
        replay(context, "1", "step", OperationType.STEP, "AcmeStateful");
        var resultType = TypeToken.get(String.class);
        var config =
                ExtensionStepConfig.<String>builder().initialState("initial").build();

        var future = new ExtensionOperationImpl(context, "1", "step", null)
                .stepAsync("AcmeStateful", resultType, state -> ExtensionStepResult.succeed(state + "-done"), config);

        assertOperation(future, StepPrimitive.class, "1", "step", "AcmeStateful");
    }

    @Test
    void primitiveSelectorsCreateMatchingOperationTypesAndExactSubtypes() {
        var context = context();
        var resultType = TypeToken.get(String.class);
        replay(context, "1", "wait", OperationType.WAIT, "AcmeWait");
        replay(context, "2", "invoke", OperationType.CHAINED_INVOKE, "AcmeInvoke");
        replay(context, "3", "callback", OperationType.CALLBACK, "AcmeCallback");
        replay(context, "4", "child", OperationType.CONTEXT, "AcmeContext");

        var wait = new ExtensionOperationImpl(context, "1", "wait", null).waitAsync("AcmeWait", Duration.ofSeconds(2));
        var invoke = new ExtensionOperationImpl(context, "2", "invoke", null)
                .invokeAsync(
                        "AcmeInvoke",
                        "target",
                        "payload",
                        resultType,
                        ExtensionInvokeConfig.builder().build());
        var callback = new ExtensionOperationImpl(context, "3", "callback", null)
                .createCallback(
                        "AcmeCallback",
                        resultType,
                        ExtensionCallbackConfig.builder().build());
        var child = new ExtensionOperationImpl(context, "4", "child", null)
                .runInChildContextAsync(
                        "AcmeContext",
                        resultType,
                        () -> ExtensionContextResult.completed("result"),
                        ExtensionContextConfig.builder().build());

        assertOperation(wait, WaitPrimitive.class, "1", "wait", "AcmeWait");
        assertOperation(invoke, InvokePrimitive.class, "2", "invoke", "AcmeInvoke");
        assertOperation(callback, CallbackPrimitive.class, "3", "callback", "AcmeCallback");
        assertOperation(child, ChildContextPrimitive.class, "4", "child", "AcmeContext");
    }

    @Test
    void invalidSubtypeDoesNotClaimReservation() {
        var context = context();
        replay(context, "1", "wait", OperationType.WAIT, "Wait");
        var operation = new ExtensionOperationImpl(context, "1", "wait", null);

        assertThrows(NullPointerException.class, () -> operation.waitAsync(null, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> operation.waitAsync(" ", Duration.ofSeconds(1)));
        assertOperation(operation.waitAsync("Wait", Duration.ofSeconds(1)), WaitPrimitive.class, "1", "wait", "Wait");
    }

    @Test
    void reservationCanOnlyExecuteOnceAcrossPrimitiveSelectors() {
        var context = context();
        replay(context, "1", "only-once", OperationType.WAIT, "Wait");
        ExtensionOperation operation = new ExtensionOperationImpl(context, "1", "only-once", null);

        operation.waitAsync("Wait", Duration.ofSeconds(1));

        assertThrows(
                IllegalStateException.class,
                () -> operation.stepAsync(
                        "Step",
                        TypeToken.get(String.class),
                        state -> ExtensionStepResult.succeed("second"),
                        ExtensionStepConfig.<String>builder().build()));
    }

    private DurableContextImpl context() {
        var context = mock(DurableContextImpl.class);
        var executionManager = mock(ExecutionManager.class);
        when(context.getExecutionManager()).thenReturn(executionManager);
        when(context.getDurableConfig())
                .thenReturn(DurableConfig.builder()
                        .withExecutorService(Executors.newCachedThreadPool())
                        .build());
        return context;
    }

    private void replay(DurableContextImpl context, String id, String name, OperationType type, String subType) {
        var details = type == OperationType.STEP
                ? StepDetails.builder().result("\"result\"").build()
                : null;
        var contextDetails = type == OperationType.CONTEXT
                ? ContextDetails.builder().result("\"result\"").build()
                : null;
        var callbackDetails = type == OperationType.CALLBACK
                ? CallbackDetails.builder()
                        .callbackId("callback-id")
                        .result("\"result\"")
                        .build()
                : null;
        when(context.getExecutionManager().getOperationAndUpdateReplayState(id))
                .thenReturn(Operation.builder()
                        .id(id)
                        .name(name)
                        .type(type)
                        .subType(subType)
                        .status(OperationStatus.SUCCEEDED)
                        .stepDetails(details)
                        .contextDetails(contextDetails)
                        .callbackDetails(callbackDetails)
                        .build());
    }

    private void assertOperation(
            Object future, Class<? extends BasePrimitive> type, String id, String name, String subType) {
        var operation = assertInstanceOf(type, future);
        assertEquals(id, operation.getOperationId());
        assertEquals(name, operation.getName());
        assertEquals(subType, operation.getSubTypeValue());
    }
}
