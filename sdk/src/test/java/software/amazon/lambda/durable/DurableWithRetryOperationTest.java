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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextFunction;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.operation.DurableWithRetryOperation;

class DurableWithRetryOperationTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void retryBodyUsesSupplierAndScopedAttempt() {
        var context = mock(CurrentExtensionContext.class);
        var parent = mock(ExtensionOperation.class);
        var future = mockIntegerFuture();
        when(context.reserve("retry")).thenReturn(parent);
        when(future.get()).thenReturn(1);
        when(parent.runInChildContextAsync(
                        eq(OperationSubType.WITH_RETRY.getValue()),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(future);
        BaseContextImpl.setCurrentContext(context);

        assertEquals(1, DurableWithRetryOperation.withRetry("retry", () -> WithRetryContext.getCurrentContext()
                .getAttempt()));

        var function = extensionFunction();
        verify(parent)
                .runInChildContextAsync(
                        eq(OperationSubType.WITH_RETRY.getValue()),
                        any(TypeToken.class),
                        function.capture(),
                        any(ExtensionContextConfig.class));
        try (var ignored = BaseContextImpl.attachCurrentContext(context)) {
            assertEquals(1, function.getValue().apply().result());
        }
        assertThrows(IllegalStateException.class, WithRetryContext::getCurrentContext);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<ExtensionContextFunction<Object>> extensionFunction() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(ExtensionContextFunction.class);
    }

    @SuppressWarnings("unchecked")
    private DurableFuture<Integer> mockIntegerFuture() {
        return mock(DurableFuture.class);
    }

    private interface CurrentExtensionContext extends DurableContext, ExtensionContext {}
}
