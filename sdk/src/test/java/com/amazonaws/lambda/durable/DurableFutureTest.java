// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.lambda.durable.operation.DurableOperation;
import java.util.List;
import org.junit.jupiter.api.Test;

class DurableFutureTest {

    @Test
    void allOfVarargsReturnsResultsInOrder() {
        var op1 = mockOperation("first");
        var op2 = mockOperation("second");
        var op3 = mockOperation("third");

        var future1 = new DurableFuture<>(op1);
        var future2 = new DurableFuture<>(op2);
        var future3 = new DurableFuture<>(op3);

        var results = DurableFuture.allOf(future1, future2, future3);

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

        var futures = List.of(new DurableFuture<>(op1), new DurableFuture<>(op2), new DurableFuture<>(op3));

        var results = DurableFuture.allOf(futures);

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
        var future = new DurableFuture<>(op);

        var results = DurableFuture.allOf(future);

        assertEquals(List.of("only"), results);
    }

    @Test
    void allOfPropagatesException() {
        var op1 = mockOperation("first");
        @SuppressWarnings("unchecked")
        DurableOperation<String> op2 = mock(DurableOperation.class);
        when(op2.get()).thenThrow(new RuntimeException("Step failed"));

        var future1 = new DurableFuture<>(op1);
        var future2 = new DurableFuture<>(op2);

        assertThrows(RuntimeException.class, () -> DurableFuture.allOf(future1, future2));
    }

    @SuppressWarnings("unchecked")
    private <T> DurableOperation<T> mockOperation(T result) {
        DurableOperation<T> op = mock(DurableOperation.class);
        when(op.get()).thenReturn(result);
        return op;
    }
}
