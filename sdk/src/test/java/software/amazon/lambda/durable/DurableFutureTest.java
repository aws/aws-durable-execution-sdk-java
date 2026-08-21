// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;

class DurableFutureTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void allOfVarargsReturnsResultsInOrder() {
        var op1 = mockOperation("first");
        var op2 = mockOperation("second");
        var op3 = mockOperation("third");

        var results = DurableFuture.allOf(op1, op2, op3);

        assertEquals(List.of("first", "second", "third"), results);
        verify(op1).get();
        verify(op2).get();
        verify(op3).get();
    }

    @Test
    void allOfListReturnsResultsInOrder() {
        var op1 = mockOperation(1);
        var op2 = mockOperation(2);
        var op3 = mockOperation(3);

        var results = DurableFuture.allOf(List.of(op1, op2, op3));

        assertEquals(List.of(1, 2, 3), results);
    }

    @Test
    void allOfVarargsEmptyReturnsEmptyList() {
        var results = DurableFuture.<String>allOf();

        assertTrue(results.isEmpty());
    }

    @Test
    void allOfListEmptyReturnsEmptyList() {
        var results = DurableFuture.allOf(List.<DurableFuture<String>>of());

        assertTrue(results.isEmpty());
    }

    @Test
    void allOfSingleFutureReturnsSingleResult() {
        var op = mockOperation("only");

        var results = DurableFuture.allOf(op);

        assertEquals(List.of("only"), results);
    }

    @Test
    void allOfPropagatesException() {
        var op1 = mockOperation("first");
        @SuppressWarnings("unchecked")
        DurableFuture<String> op2 = mock(DurableFuture.class);
        when(op2.get()).thenThrow(new RuntimeException("Step failed"));

        assertThrows(RuntimeException.class, () -> DurableFuture.allOf(op1, op2));
    }

    @Test
    void anyOfSupportsPublicDurableFutureImplementations() {
        var pending = new TestFuture<>("pending");
        var completed = new TestFuture<>("completed");
        completed.complete();

        var result = DurableFuture.anyOf(pending, completed);

        assertEquals("completed", result);
    }

    @Test
    void anyOfUsesExecutionManagerWhenCalledFromDurableContext() {
        var context = mock(BaseContextImpl.class);
        var executionManager = mock(ExecutionManager.class);
        when(context.getExecutionManager()).thenReturn(executionManager);
        when(executionManager.awaitFuture(any())).thenAnswer(invocation -> {
            CompletableFuture<?> future = invocation.getArgument(0);
            return future.join();
        });
        BaseContextImpl.setCurrentContext(context);
        var completed = new TestFuture<>("completed");
        completed.complete();

        assertEquals("completed", DurableFuture.anyOf(completed));
        verify(executionManager).awaitFuture(any());
    }

    @SuppressWarnings("unchecked")
    private <T> DurableFuture<T> mockOperation(T result) {
        DurableFuture<T> op = mock(DurableFuture.class);
        when(op.get()).thenReturn(result);
        return op;
    }

    private static final class TestFuture<T> implements DurableFuture<T> {
        private final T result;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private TestFuture(T result) {
            this.result = result;
        }

        @Override
        public T get() {
            return result;
        }

        @Override
        public CompletableFuture<Void> completionFuture() {
            return completion;
        }

        private void complete() {
            completion.complete(null);
        }
    }
}
