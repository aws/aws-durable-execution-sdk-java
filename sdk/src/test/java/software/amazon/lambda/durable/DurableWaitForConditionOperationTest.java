// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepFunction;
import software.amazon.lambda.durable.extension.ExtensionStepResult;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.operation.DurableWaitForConditionOperation;
import software.amazon.lambda.durable.operation.DurableWaitForConditionOperation.WaitForConditionResult;

class DurableWaitForConditionOperationTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void conditionFunctionReceivesOnlyStateAndUsesStepContextFromTls() {
        var context = mock(CurrentExtensionContext.class);
        var reservation = mock(ExtensionOperation.class);
        var future = CompletableFuture.completedFuture("VALUE");
        var stepContext = mock(StepContext.class);
        when(context.reserve("condition")).thenReturn(reservation);
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
            var result = (ExtensionStepResult.Succeeded<String>)
                    check.getValue().apply("value").toCompletableFuture().join();
            assertEquals("VALUE", result.value());
        }
    }

    @Test
    void resultFactoriesRepresentPollingDecision() {
        var completed = WaitForConditionResult.stopPolling("done");
        var pending = WaitForConditionResult.continuePolling("next");

        assertEquals("done", completed.value());
        assertTrue(completed.isDone());
        assertEquals("next", pending.value());
        assertFalse(pending.isDone());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<ExtensionStepFunction<String>> extensionFunction() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(ExtensionStepFunction.class);
    }

    private interface CurrentExtensionContext extends DurableContext, ExtensionContext {}
}
