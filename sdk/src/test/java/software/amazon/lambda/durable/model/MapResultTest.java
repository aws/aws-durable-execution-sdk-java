// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MapResultTest {

    private static MapResult.MapError testError(String message) {
        return new MapResult.MapError("java.lang.RuntimeException", message, null);
    }

    @Test
    void empty_returnsZeroSizeResult() {
        var result = MapResult.<String>empty();

        assertEquals(0, result.size());
        assertTrue(result.allSucceeded());
        assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionReason());
        assertTrue(result.results().isEmpty());
        assertTrue(result.succeeded().isEmpty());
        assertTrue(result.failed().isEmpty());
    }

    @Test
    void allSucceeded_trueWhenNoErrors() {
        var result = new MapResult<>(
                List.of(MapResult.MapResultItem.succeeded("a"), MapResult.MapResultItem.succeeded("b")),
                ConcurrencyCompletionStatus.ALL_COMPLETED);

        assertTrue(result.allSucceeded());
        assertEquals(2, result.size());
        assertEquals("a", result.getResult(0));
        assertEquals("b", result.getResult(1));
        assertNull(result.getError(0));
        assertNull(result.getError(1));
    }

    @Test
    void allSucceeded_falseWhenAnyError() {
        var error = testError("fail");
        var result = new MapResult<>(
                List.of(MapResult.MapResultItem.succeeded("a"), MapResult.MapResultItem.<String>failed(error)),
                ConcurrencyCompletionStatus.ALL_COMPLETED);

        assertFalse(result.allSucceeded());
    }

    @Test
    void getResult_returnsNullForFailedItem() {
        var error = testError("fail");
        var result = new MapResult<>(
                List.of(MapResult.MapResultItem.succeeded("a"), MapResult.MapResultItem.<String>failed(error)),
                ConcurrencyCompletionStatus.ALL_COMPLETED);

        assertEquals("a", result.getResult(0));
        assertNull(result.getResult(1));
    }

    @Test
    void getError_returnsNullForSucceededItem() {
        var error = testError("fail");
        var result = new MapResult<>(
                List.of(MapResult.MapResultItem.succeeded("a"), MapResult.MapResultItem.<String>failed(error)),
                ConcurrencyCompletionStatus.ALL_COMPLETED);

        assertNull(result.getError(0));
        assertSame(error, result.getError(1));
    }

    @Test
    void succeeded_filtersNullResults() {
        var result = new MapResult<>(
                List.of(
                        MapResult.MapResultItem.succeeded("a"),
                        MapResult.MapResultItem.<String>failed(testError("fail")),
                        MapResult.MapResultItem.succeeded("c")),
                ConcurrencyCompletionStatus.ALL_COMPLETED);

        assertEquals(List.of("a", "c"), result.succeeded());
    }

    @Test
    void failed_filtersNullErrors() {
        var error = testError("fail");
        var result = new MapResult<>(
                List.of(
                        MapResult.MapResultItem.succeeded("a"),
                        MapResult.MapResultItem.<String>failed(error),
                        MapResult.MapResultItem.succeeded("c")),
                ConcurrencyCompletionStatus.ALL_COMPLETED);

        var failures = result.failed();
        assertEquals(1, failures.size());
        assertSame(error, failures.get(0));
    }

    @Test
    void completionReason_preserved() {
        var result = new MapResult<>(
                List.of(MapResult.MapResultItem.succeeded("a")), ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED);

        assertEquals(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED, result.completionReason());
    }

    @Test
    void items_returnsUnmodifiableList() {
        var result = new MapResult<>(
                List.of(MapResult.MapResultItem.succeeded("a")), ConcurrencyCompletionStatus.ALL_COMPLETED);

        assertThrows(
                UnsupportedOperationException.class, () -> result.items().add(MapResult.MapResultItem.succeeded("b")));
    }

    @Test
    void getItem_returnsMapResultItem() {
        var result = new MapResult<>(
                List.of(
                        MapResult.MapResultItem.succeeded("a"),
                        MapResult.MapResultItem.<String>failed(testError("fail"))),
                ConcurrencyCompletionStatus.ALL_COMPLETED);

        assertEquals(MapResult.MapResultItem.Status.SUCCEEDED, result.getItem(0).status());
        assertEquals("a", result.getItem(0).result());
        assertNull(result.getItem(0).error());

        assertEquals(MapResult.MapResultItem.Status.FAILED, result.getItem(1).status());
        assertNull(result.getItem(1).result());
        assertNotNull(result.getItem(1).error());
    }

    @Test
    void notStartedItems_haveNotStartedStatusAndNullResultAndError() {
        var result = new MapResult<>(
                List.of(MapResult.MapResultItem.succeeded("a"), MapResult.MapResultItem.<String>skipped()),
                ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED);

        assertEquals(MapResult.MapResultItem.Status.SKIPPED, result.getItem(1).status());
        assertNull(result.getResult(1));
        assertNull(result.getError(1));
    }
}
