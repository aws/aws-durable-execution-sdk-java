// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.exception.DistributedMapException;

class DistributedMapResultTest {

    private static DistributedMapSummary summary(DistributedMapStatus status, long failureCount) {
        return new DistributedMapSummary(
                status,
                true,
                2,
                failureCount,
                2 + failureCount,
                "arn:aws:states:us-east-1:123456789012:mapRun:run-42",
                new DistributedMapError("RunFailed", "details", null, null),
                2 + failureCount);
    }

    private static DistributedMapError itemError(String message) {
        return new DistributedMapError("java.lang.RuntimeException", message, null, null);
    }

    @Test
    void succeeded_returnsAllSucceededItems() {
        var result = new DistributedMapResult<>(
                summary(DistributedMapStatus.SUCCEEDED, 1),
                List.of(
                        DistributedMapResult.Item.succeeded("a", "out-a"),
                        DistributedMapResult.Item.failed("b", itemError("fail-b")),
                        DistributedMapResult.Item.succeeded("c", "out-c")));

        var succeeded = result.succeeded();
        assertEquals(2, succeeded.size());
        assertEquals("a", succeeded.get(0).itemId());
        assertEquals("c", succeeded.get(1).itemId());
    }

    @Test
    void failed_returnsAllFailedItems() {
        var result = new DistributedMapResult<>(
                summary(DistributedMapStatus.SUCCEEDED, 1),
                List.of(
                        DistributedMapResult.Item.succeeded("a", "out-a"),
                        DistributedMapResult.Item.failed("b", itemError("fail-b"))));

        var failed = result.failed();
        assertEquals(1, failed.size());
        assertEquals("b", failed.get(0).itemId());
    }

    @Test
    void results_preservesNullOutputs() {
        var result = new DistributedMapResult<>(
                summary(DistributedMapStatus.SUCCEEDED, 0),
                List.of(
                        DistributedMapResult.Item.succeeded("a", "out-a"),
                        DistributedMapResult.Item.succeeded("b", null),
                        DistributedMapResult.Item.succeeded("c", "out-c")));

        // cardinality must match succeeded() so callers can align outputs with items
        assertEquals(3, result.results().size());
        assertEquals(Arrays.asList("out-a", null, "out-c"), result.results());
        assertEquals(result.succeeded().size(), result.results().size());
    }

    @Test
    void errors_preservesNullErrors() {
        DistributedMapResult.Item<String> withError = DistributedMapResult.Item.failed("a", itemError("fail-a"));
        var withoutError =
                new DistributedMapResult.Item<String>("b", DistributedMapResult.Item.Status.FAILED, null, null);
        var result = new DistributedMapResult<String>(
                summary(DistributedMapStatus.SUCCEEDED, 2), List.of(withError, withoutError));

        // cardinality must match failed() so callers can align errors with items
        var errors = result.errors();
        assertEquals(2, errors.size());
        assertEquals("fail-a", errors.get(0).errorMessage());
        assertNull(errors.get(1));
        assertEquals(result.failed().size(), errors.size());
    }

    @Test
    void throwIfError_throwsRunLevelOnNonSucceededStatus() {
        var result = new DistributedMapResult<>(
                summary(DistributedMapStatus.FAILED, 0), List.of(DistributedMapResult.Item.succeeded("a", "out-a")));

        var error = assertThrows(DistributedMapException.class, result::throwIfError);
        assertEquals(DistributedMapStatus.FAILED, error.status());
    }

    @Test
    void throwIfError_surfacesFirstFailedItemWhenSucceededWithFailures() {
        var result = new DistributedMapResult<>(
                summary(DistributedMapStatus.SUCCEEDED, 1),
                List.of(
                        DistributedMapResult.Item.succeeded("a", "out-a"),
                        DistributedMapResult.Item.failed("item-42", itemError("boom-item-42"))));

        var error = assertThrows(DistributedMapException.class, result::throwIfError);
        assertTrue(error.getMessage().contains("item-42 failed"), error.getMessage());
        assertTrue(error.getMessage().contains("boom-item-42"), error.getMessage());
    }

    private static String itemFailureMessage(DistributedMapError error) {
        var result = new DistributedMapResult<>(
                summary(DistributedMapStatus.SUCCEEDED, 1), List.of(DistributedMapResult.Item.failed("b", error)));
        return assertThrows(DistributedMapException.class, result::throwIfError).getMessage();
    }

    @Test
    void throwIfError_itemMessageJoinsTypeAndMessage() {
        assertEquals(
                "Distributed map item b failed: E: boom",
                itemFailureMessage(new DistributedMapError("E", "boom", null, null)));
    }

    @Test
    void throwIfError_itemMessageUsesMessageWhenTypeIsAbsent() {
        assertEquals(
                "Distributed map item b failed: boom",
                itemFailureMessage(new DistributedMapError(null, "boom", null, null)));
        assertEquals(
                "Distributed map item b failed: boom",
                itemFailureMessage(new DistributedMapError("", "boom", null, null)));
    }

    @Test
    void throwIfError_itemMessageUsesTypeWhenMessageIsAbsent() {
        assertEquals(
                "Distributed map item b failed: E", itemFailureMessage(new DistributedMapError("E", null, null, null)));
        assertEquals(
                "Distributed map item b failed: E", itemFailureMessage(new DistributedMapError("E", "", null, null)));
    }

    @Test
    void throwIfError_itemMessageFallsBackWhenTypeAndMessageAreAbsent() {
        assertEquals(
                "Distributed map item b failed", itemFailureMessage(new DistributedMapError(null, null, null, null)));
        assertEquals("Distributed map item b failed", itemFailureMessage(new DistributedMapError("", "", null, null)));
        assertEquals("Distributed map item b failed", itemFailureMessage(null));
    }

    @Test
    void throwIfError_doesNothingOnFullSuccess() {
        var result = new DistributedMapResult<>(
                summary(DistributedMapStatus.SUCCEEDED, 0),
                List.of(
                        DistributedMapResult.Item.succeeded("a", "out-a"),
                        DistributedMapResult.Item.succeeded("b", "out-b")));

        assertDoesNotThrow(result::throwIfError);
    }

    @Test
    void delegators_returnComposedSummaryValues() {
        var summary = summary(DistributedMapStatus.SUCCEEDED, 1);
        var result = new DistributedMapResult<>(summary, List.of(DistributedMapResult.Item.succeeded("a", "out-a")));

        assertEquals(summary.status(), result.status());
        assertEquals(summary.sourceExhausted(), result.sourceExhausted());
        assertEquals(summary.successCount(), result.successCount());
        assertEquals(summary.failureCount(), result.failureCount());
        assertEquals(summary.readCount(), result.readCount());
        assertEquals(summary.totalCount(), result.totalCount());
        assertEquals(summary.distributedMapRunArn(), result.distributedMapRunArn());
        assertEquals(summary.error(), result.error());
        assertEquals(summary.hasFailure(), result.hasFailure());
    }

    @Test
    void items_defaultToEmptyWhenNull() {
        var result = new DistributedMapResult<String>(summary(DistributedMapStatus.SUCCEEDED, 0), null);

        assertTrue(result.items().isEmpty());
    }

    @Test
    void of_usesFullyQualifiedClassName() {
        var error = DistributedMapError.of(new IllegalStateException("boom"));

        assertEquals("java.lang.IllegalStateException", error.errorType());
        assertEquals("boom", error.errorMessage());
        assertFalse(error.stackTrace().isEmpty());
    }

    @Test
    void of_preservesNullMessage() {
        var error = DistributedMapError.of(new RuntimeException());

        assertEquals("java.lang.RuntimeException", error.errorType());
        assertNull(error.errorMessage());
    }
}
