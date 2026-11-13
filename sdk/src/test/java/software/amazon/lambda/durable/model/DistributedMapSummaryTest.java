// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.exception.DistributedMapException;

class DistributedMapSummaryTest {

    private static DistributedMapSummary summary(
            DistributedMapStatus status, long failureCount, String arn, String errorMessage) {
        var error = errorMessage != null ? new DistributedMapError("RunFailed", errorMessage, null, null) : null;
        return new DistributedMapSummary(status, true, 5 - failureCount, failureCount, 5, arn, error, 5L);
    }

    @Test
    void distributedMapRunArn_exposedAsGiven() {
        var s = summary(
                DistributedMapStatus.SUCCEEDED,
                0,
                "arn:aws:lambda:us-east-1:123456789012:distributed-map-run/abc",
                null);

        assertEquals("arn:aws:lambda:us-east-1:123456789012:distributed-map-run/abc", s.distributedMapRunArn());
    }

    @Test
    void distributedMapRunArn_nullWhenAbsent() {
        var s = summary(DistributedMapStatus.SUCCEEDED, 0, null, null);

        assertNull(s.distributedMapRunArn());
    }

    @Test
    void hasFailure_trueWhenFailureCountPositive() {
        assertTrue(summary(DistributedMapStatus.SUCCEEDED, 3, "arn:x", null).hasFailure());
    }

    @Test
    void hasFailure_falseWhenNoFailures() {
        assertFalse(summary(DistributedMapStatus.SUCCEEDED, 0, "arn:x", null).hasFailure());
    }

    @Test
    void throwIfError_throwsOnNonSucceededStatus() {
        var s = summary(DistributedMapStatus.FAILED, 0, "arn:x", "run blew up");

        var error = assertThrows(DistributedMapException.class, s::throwIfError);
        assertEquals(DistributedMapStatus.FAILED, error.status());
        assertEquals("run blew up", error.error().errorMessage());
        assertTrue(error.getMessage().contains("run blew up"));
    }

    @Test
    void throwIfError_throwsWhenSucceededWithFailures() {
        var s = summary(DistributedMapStatus.SUCCEEDED, 2, "arn:x", null);

        assertThrows(DistributedMapException.class, s::throwIfError);
    }

    @Test
    void throwIfError_doesNothingOnFullSuccess() {
        var s = summary(DistributedMapStatus.SUCCEEDED, 0, "arn:x", null);

        assertDoesNotThrow(s::throwIfError);
    }
}
