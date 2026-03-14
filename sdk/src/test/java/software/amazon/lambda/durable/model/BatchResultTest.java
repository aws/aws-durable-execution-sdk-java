// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BatchResultTest {

    @Test
    void empty_returnsZeroSizeResult() {
        var result = BatchResult.<String>empty();

        assertEquals(0, result.size());
        assertTrue(result.allSucceeded());
        assertEquals(CompletionReason.ALL_COMPLETED, result.completionReason());
        assertTrue(result.results().isEmpty());
        assertTrue(result.succeeded().isEmpty());
        assertTrue(result.failed().isEmpty());
    }

    @Test
    void allSucceeded_trueWhenNoErrors() {
        var result = new BatchResult<>(List.of("a", "b"), Arrays.asList(null, null), CompletionReason.ALL_COMPLETED);

        assertTrue(result.allSucceeded());
        assertEquals(2, result.size());
        assertEquals("a", result.getResult(0));
        assertEquals("b", result.getResult(1));
        assertNull(result.getError(0));
        assertNull(result.getError(1));
    }

    @Test
    void allSucceeded_falseWhenAnyError() {
        var error = new RuntimeException("fail");
        var result =
                new BatchResult<>(Arrays.asList("a", null), Arrays.asList(null, error), CompletionReason.ALL_COMPLETED);

        assertFalse(result.allSucceeded());
    }

    @Test
    void getResult_returnsNullForFailedItem() {
        var error = new RuntimeException("fail");
        var result =
                new BatchResult<>(Arrays.asList("a", null), Arrays.asList(null, error), CompletionReason.ALL_COMPLETED);

        assertEquals("a", result.getResult(0));
        assertNull(result.getResult(1));
    }

    @Test
    void getError_returnsNullForSucceededItem() {
        var error = new RuntimeException("fail");
        var result =
                new BatchResult<>(Arrays.asList("a", null), Arrays.asList(null, error), CompletionReason.ALL_COMPLETED);

        assertNull(result.getError(0));
        assertSame(error, result.getError(1));
    }

    @Test
    void succeeded_filtersNullResults() {
        var result = new BatchResult<>(
                Arrays.asList("a", null, "c"),
                Arrays.asList(null, new RuntimeException(), null),
                CompletionReason.ALL_COMPLETED);

        assertEquals(List.of("a", "c"), result.succeeded());
    }

    @Test
    void failed_filtersNullErrors() {
        var error = new RuntimeException("fail");
        var result = new BatchResult<>(
                Arrays.asList("a", null, "c"), Arrays.asList(null, error, null), CompletionReason.ALL_COMPLETED);

        var failures = result.failed();
        assertEquals(1, failures.size());
        assertSame(error, failures.get(0));
    }

    @Test
    void completionReason_preserved() {
        var result = new BatchResult<>(
                List.of("a"), Arrays.asList((Throwable) null), CompletionReason.MIN_SUCCESSFUL_REACHED);

        assertEquals(CompletionReason.MIN_SUCCESSFUL_REACHED, result.completionReason());
    }

    @Test
    void results_returnsUnmodifiableList() {
        var result = new BatchResult<>(List.of("a"), Arrays.asList((Throwable) null), CompletionReason.ALL_COMPLETED);

        assertThrows(UnsupportedOperationException.class, () -> result.results().add("b"));
    }
}
