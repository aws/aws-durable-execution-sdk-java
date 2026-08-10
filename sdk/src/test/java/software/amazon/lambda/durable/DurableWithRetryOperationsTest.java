// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.context.BaseContextImpl;

class DurableWithRetryOperationsTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void retryBodyUsesSupplierAndScopedAttempt() {
        var context = mock(DurableContext.class);
        BaseContextImpl.setCurrentContext(context);

        DurableWithRetryOperations.withRetry("retry", () -> WithRetryContext.getCurrentContext()
                .getAttempt());

        @SuppressWarnings("unchecked")
        var operation = (ArgumentCaptor<BiFunction<Integer, DurableContext, Integer>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(BiFunction.class);
        verify(context).withRetry(eq("retry"), operation.capture());
        assertEquals(2, operation.getValue().apply(2, mock(DurableContext.class)));
        assertThrows(IllegalStateException.class, WithRetryContext::getCurrentContext);
    }
}
