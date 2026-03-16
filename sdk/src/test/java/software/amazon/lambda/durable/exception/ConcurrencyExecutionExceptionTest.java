// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConcurrencyExecutionExceptionTest {

    @Test
    void testConstructionWithCounts() {
        var failures = List.<Throwable>of(new RuntimeException("branch failed"));
        var exception = new ConcurrencyExecutionException(2, 1, 3, failures);

        assertEquals(2, exception.getSuccessCount());
        assertEquals(1, exception.getFailureCount());
        assertEquals(3, exception.getTotalItems());
        assertEquals(1, exception.getItemFailures().size());
        assertEquals("branch failed", exception.getItemFailures().get(0).getMessage());
    }

    @Test
    void testConstructionWithNullFailures() {
        var exception = new ConcurrencyExecutionException(0, 3, 3, null);

        assertEquals(0, exception.getSuccessCount());
        assertEquals(3, exception.getFailureCount());
        assertEquals(3, exception.getTotalItems());
        assertTrue(exception.getItemFailures().isEmpty());
    }

    @Test
    void testConstructionWithEmptyFailures() {
        var exception = new ConcurrencyExecutionException(5, 0, 5, Collections.emptyList());

        assertEquals(5, exception.getSuccessCount());
        assertEquals(0, exception.getFailureCount());
        assertEquals(5, exception.getTotalItems());
        assertTrue(exception.getItemFailures().isEmpty());
    }

    @Test
    void testConstructionWithMultipleFailures() {
        var failures = List.<Throwable>of(
                new RuntimeException("fail 1"), new IllegalStateException("fail 2"), new RuntimeException("fail 3"));
        var exception = new ConcurrencyExecutionException(2, 3, 5, failures);

        assertEquals(3, exception.getItemFailures().size());
        assertInstanceOf(
                IllegalStateException.class, exception.getItemFailures().get(1));
    }

    @Test
    void testMessageFormatting() {
        var exception = new ConcurrencyExecutionException(3, 2, 5, Collections.emptyList());

        assertEquals("Concurrency operation failed: 3/5 items succeeded, 2 failed", exception.getMessage());
    }

    @Test
    void testMessageFormattingAllFailed() {
        var exception = new ConcurrencyExecutionException(0, 4, 4, Collections.emptyList());

        assertEquals("Concurrency operation failed: 0/4 items succeeded, 4 failed", exception.getMessage());
    }

    @Test
    void testIsDurableExecutionException() {
        var exception = new ConcurrencyExecutionException(1, 1, 2, Collections.emptyList());

        assertInstanceOf(DurableExecutionException.class, exception);
    }

    @Test
    void testItemFailuresListIsUnmodifiable() {
        var failures = List.<Throwable>of(new RuntimeException("fail"));
        var exception = new ConcurrencyExecutionException(0, 1, 1, failures);

        var items = exception.getItemFailures();
        assertThrows(UnsupportedOperationException.class, () -> {
            items.add(new RuntimeException("should not work"));
        });
    }
}
