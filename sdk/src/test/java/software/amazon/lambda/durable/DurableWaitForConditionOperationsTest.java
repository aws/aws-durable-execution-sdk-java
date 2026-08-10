// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.model.WaitForConditionResult;

class DurableWaitForConditionOperationsTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void conditionFunctionReceivesOnlyStateAndUsesStepContextFromTls() {
        var context = mock(DurableContext.class);
        var stepContext = mock(StepContext.class);
        BaseContextImpl.setCurrentContext(context);

        DurableWaitForConditionOperations.waitForCondition("condition", String.class, state -> {
            assertEquals(stepContext, StepContext.getCurrentContext());
            return WaitForConditionResult.stopPolling(state.toUpperCase());
        });

        @SuppressWarnings("unchecked")
        var check = (ArgumentCaptor<BiFunction<String, StepContext, WaitForConditionResult<String>>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(BiFunction.class);
        verify(context).waitForCondition(eq("condition"), eq(String.class), check.capture());
        try (var ignored = BaseContextImpl.attachCurrentContext(stepContext)) {
            assertEquals(
                    WaitForConditionResult.stopPolling("VALUE"),
                    check.getValue().apply("value", stepContext));
        }
    }
}
