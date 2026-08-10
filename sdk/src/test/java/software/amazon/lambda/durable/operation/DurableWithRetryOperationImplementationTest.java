// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.WithRetryConfig;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextFunction;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.retry.RetryDecision;

class DurableWithRetryOperationImplementationTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void executePreservesContextTopologyAndDurableBackoff() {
        var context = mock(CurrentExtensionContext.class);
        var parent = mock(ExtensionOperation.class);
        var future = mockObjectFuture();
        when(context.reserve("transaction")).thenReturn(parent);
        when(parent.runInChildContextAsync(
                        eq(OperationSubType.WITH_RETRY.getValue()),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(future);
        var attempts = new ArrayList<Integer>();
        var config = WithRetryConfig.builder()
                .retryStrategy((error, attempt) ->
                        attempt == 1 ? RetryDecision.retry(Duration.ofSeconds(5)) : RetryDecision.fail())
                .wrapInChildContext(true)
                .build();

        var actual = DurableWithRetryOperation.withRetryAsync(
                context,
                "transaction",
                (attempt, child) -> {
                    attempts.add(attempt);
                    assertSame(child, DurableContext.getCurrentContext());
                    if (attempt == 1) {
                        throw new RuntimeException("retry");
                    }
                    return "done";
                },
                config.toOperationConfig());

        assertSame(future, actual);
        var function = extensionFunction();
        var contextConfig = ArgumentCaptor.forClass(ExtensionContextConfig.class);
        verify(parent)
                .runInChildContextAsync(
                        eq(OperationSubType.WITH_RETRY.getValue()),
                        any(TypeToken.class),
                        function.capture(),
                        contextConfig.capture());
        assertFalse(contextConfig.getValue().childContextConfig().isVirtual());

        var child = mock(CurrentExtensionContext.class);
        var wait = mock(ExtensionOperation.class);
        var waitFuture = mockVoidFuture();
        when(child.reserve("transaction-backoff-1")).thenReturn(wait);
        when(wait.waitAsync(OperationSubType.WAIT.getValue(), Duration.ofSeconds(5)))
                .thenReturn(waitFuture);
        BaseContextImpl.setCurrentContext(child);

        var result = function.getValue().apply();

        assertEquals("done", result.result());
        assertEquals(1, attempts.get(0));
        assertEquals(2, attempts.get(1));
        verify(wait).waitAsync(OperationSubType.WAIT.getValue(), Duration.ofSeconds(5));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<ExtensionContextFunction<Object>> extensionFunction() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(ExtensionContextFunction.class);
    }

    @SuppressWarnings("unchecked")
    private DurableFuture<Object> mockObjectFuture() {
        return mock(DurableFuture.class);
    }

    @SuppressWarnings("unchecked")
    private DurableFuture<Void> mockVoidFuture() {
        return mock(DurableFuture.class);
    }

    private interface CurrentExtensionContext extends DurableContext, ExtensionContext {}
}
