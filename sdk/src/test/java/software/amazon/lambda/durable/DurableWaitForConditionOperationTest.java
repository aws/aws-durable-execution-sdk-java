// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepFunction;
import software.amazon.lambda.durable.extension.ExtensionStepResult;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.operation.DurableWaitForConditionOperation;

class DurableWaitForConditionOperationTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void conditionFunctionReceivesOnlyStateAndUsesStepContextFromTls() {
        var context = mock(CurrentExtensionContext.class);
        var reservation = mock(ExtensionOperation.class);
        var future = mockStringFuture();
        var stepContext = mock(StepContext.class);
        when(context.reserve("condition")).thenReturn(reservation);
        when(future.get()).thenReturn("VALUE");
        when(reservation.stepAsync(
                        eq(OperationSubType.WAIT_FOR_CONDITION.getValue()),
                        eq(TypeToken.get(String.class)),
                        any(ExtensionStepFunction.class),
                        any(ExtensionStepConfig.class)))
                .thenReturn(future);
        BaseContextImpl.setCurrentContext(context);

        assertEquals("VALUE", DurableWaitForConditionOperation.waitForCondition("condition", String.class, state -> {
            assertEquals(stepContext, StepContext.getCurrentContext());
            return WaitForConditionResult.stopPolling(state.toUpperCase());
        }));

        var check = extensionFunction();
        verify(reservation)
                .stepAsync(
                        eq(OperationSubType.WAIT_FOR_CONDITION.getValue()),
                        eq(TypeToken.get(String.class)),
                        check.capture(),
                        any(ExtensionStepConfig.class));
        try (var ignored = BaseContextImpl.attachCurrentContext(stepContext)) {
            var result =
                    (ExtensionStepResult.Succeeded<String>) check.getValue().apply("value");
            assertEquals("VALUE", result.value());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<ExtensionStepFunction<String>> extensionFunction() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(ExtensionStepFunction.class);
    }

    @SuppressWarnings("unchecked")
    private DurableFuture<String> mockStringFuture() {
        return mock(DurableFuture.class);
    }

    private interface CurrentExtensionContext extends DurableContext, ExtensionContext {}
}
