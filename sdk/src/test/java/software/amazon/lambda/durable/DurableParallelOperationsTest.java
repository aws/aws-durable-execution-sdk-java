// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.config.ParallelBranchConfig;
import software.amazon.lambda.durable.context.BaseContextImpl;

class DurableParallelOperationsTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void parallelBranchesAcceptContextFreeSuppliers() {
        var context = mock(DurableContext.class);
        var parallel = mock(ParallelDurableFuture.class, CALLS_REAL_METHODS);
        var branchFuture = mockStringFuture();
        BaseContextImpl.setCurrentContext(context);
        when(context.parallel("parallel")).thenReturn(parallel);
        when(parallel.branch(eq("branch"), any(TypeToken.class), any(Function.class), any(ParallelBranchConfig.class)))
                .thenReturn(branchFuture);

        var result = DurableParallelOperations.parallel("parallel");
        var resultFuture = result.branch("branch", String.class, () -> "result");

        assertSame(parallel, result);
        assertSame(branchFuture, resultFuture);
        @SuppressWarnings("unchecked")
        var function = (ArgumentCaptor<Function<DurableContext, String>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(Function.class);
        verify(parallel)
                .branch(eq("branch"), any(TypeToken.class), function.capture(), any(ParallelBranchConfig.class));
        assertEquals("result", function.getValue().apply(mock(DurableContext.class)));
    }

    @SuppressWarnings("unchecked")
    private DurableFuture<String> mockStringFuture() {
        return mock(DurableFuture.class);
    }
}
