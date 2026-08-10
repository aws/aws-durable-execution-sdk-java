// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextFunction;
import software.amazon.lambda.durable.extension.ExtensionOperation;

class DurableParallelOperationsTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void parallelBranchesAcceptContextFreeSuppliers() {
        var context = mock(CurrentContext.class);
        var parent = mock(ExtensionOperation.class);
        var parentFuture = mockParallelResultFuture();
        BaseContextImpl.setCurrentContext(context);
        when(context.getDurableConfig()).thenReturn(DurableConfig.builder().build());
        when(context.reserve("parallel")).thenReturn(parent);
        when(parent.runInChildContextAsync(
                        any(String.class),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(parentFuture);

        var result = DurableParallelOperations.parallel("parallel");
        result.branch("branch", String.class, () -> "result");

        verify(context).reserve("parallel");
        verify(context, never()).parallel(eq("parallel"), any(ParallelConfig.class));
    }

    @SuppressWarnings("unchecked")
    private DurableFuture<software.amazon.lambda.durable.model.ParallelResult> mockParallelResultFuture() {
        return mock(DurableFuture.class);
    }

    private interface CurrentContext extends DurableContext, ExtensionContext {}
}
