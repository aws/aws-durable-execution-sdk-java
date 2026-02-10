// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.lambda.durable.operation.BaseDurableOperation;
import java.util.List;
import org.junit.jupiter.api.Test;

class DurableFutureTest {

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
        BaseDurableOperation<String> op2 = mock(BaseDurableOperation.class);
        when(op2.get()).thenThrow(new RuntimeException("Step failed"));

        assertThrows(RuntimeException.class, () -> DurableFuture.allOf(op1, op2));
    }

    @SuppressWarnings("unchecked")
    private <T> BaseDurableOperation<T> mockOperation(T result) {
        BaseDurableOperation<T> op = mock(BaseDurableOperation.class);
        when(op.get()).thenReturn(result);
        return op;
    }
}
