// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MapResultTest {

    @Test
    void empty_returnsZeroSizeResult() {
        var result = MapResult.<String>empty();

        assertEquals(0, result.size());
        assertTrue(result.allSucceeded());
        assertEquals(CompletionReason.ALL_COMPLETED, result.completionReason());
        assertTrue(result.results().isEmpty());
        assertTrue(result.succeeded().isEmpty());
        assertTrue(result.failed().isEmpty());
    }

    @Test
    void allSucceeded_trueWhenNoErrors() {
        var result = new MapResult<>(
                List.of(MapResultItem.success("a"), MapResultItem.success("b")), CompletionReason.ALL_COMPLETED);

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
        var result = new MapResult<>(
                List.of(MapResultItem.success("a"), MapResultItem.<String>failure(error)),
                CompletionReason.ALL_COMPLETED);

        assertFalse(result.allSucceeded());
    }

    @Test
    void getResult_returnsNullForFailedItem() {
        var error = new RuntimeException("fail");
        var result = new MapResult<>(
                List.of(MapResultItem.success("a"), MapResultItem.<String>failure(error)),
                CompletionReason.ALL_COMPLETED);

        assertEquals("a", result.getResult(0));
        assertNull(result.getResult(1));
    }

    @Test
    void getError_returnsNullForSucceededItem() {
        var error = new RuntimeException("fail");
        var result = new MapResult<>(
                List.of(MapResultItem.success("a"), MapResultItem.<String>failure(error)),
                CompletionReason.ALL_COMPLETED);

        assertNull(result.getError(0));
        assertSame(error, result.getError(1));
    }

    @Test
    void succeeded_filtersNullResults() {
        var result = new MapResult<>(
                List.of(
                        MapResultItem.success("a"),
                        MapResultItem.<String>failure(new RuntimeException()),
                        MapResultItem.success("c")),
                CompletionReason.ALL_COMPLETED);

        assertEquals(List.of("a", "c"), result.succeeded());
    }

    @Test
    void failed_filtersNullErrors() {
        var error = new RuntimeException("fail");
        var result = new MapResult<>(
                List.of(MapResultItem.success("a"), MapResultItem.<String>failure(error), MapResultItem.success("c")),
                CompletionReason.ALL_COMPLETED);

        var failures = result.failed();
        assertEquals(1, failures.size());
        assertSame(error, failures.get(0));
    }

    @Test
    void completionReason_preserved() {
        var result = new MapResult<>(List.of(MapResultItem.success("a")), CompletionReason.MIN_SUCCESSFUL_REACHED);

        assertEquals(CompletionReason.MIN_SUCCESSFUL_REACHED, result.completionReason());
    }

    @Test
    void items_returnsUnmodifiableList() {
        var result = new MapResult<>(List.of(MapResultItem.success("a")), CompletionReason.ALL_COMPLETED);

        assertThrows(UnsupportedOperationException.class, () -> result.items().add(MapResultItem.success("b")));
    }

    @Test
    void getItem_returnsMapResultItem() {
        var result = new MapResult<>(
                List.of(MapResultItem.success("a"), MapResultItem.<String>failure(new RuntimeException("fail"))),
                CompletionReason.ALL_COMPLETED);

        assertEquals(MapResultItem.Status.SUCCEEDED, result.getItem(0).status());
        assertEquals("a", result.getItem(0).result());
        assertNull(result.getItem(0).error());

        assertEquals(MapResultItem.Status.FAILED, result.getItem(1).status());
        assertNull(result.getItem(1).result());
        assertNotNull(result.getItem(1).error());
    }

    @Test
    void notStartedItems_haveNullStatusResultAndError() {
        var result = new MapResult<>(
                List.of(MapResultItem.success("a"), MapResultItem.<String>notStarted()),
                CompletionReason.MIN_SUCCESSFUL_REACHED);

        assertNull(result.getItem(1).status());
        assertNull(result.getResult(1));
        assertNull(result.getError(1));
    }
}
