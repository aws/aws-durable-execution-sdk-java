// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.exception.DistributedMapException;
import software.amazon.lambda.durable.exception.DurableOperationException;

class DistributedMapSummaryTest {

    private static final Operation OPERATION = Operation.builder()
            .id("dmap-1")
            .type(OperationType.CONTEXT)
            .status(OperationStatus.FAILED)
            .build();

    private static DistributedMapSummary summary(
            DistributedMapStatus status, long failureCount, String arn, String errorMessage) {
        var error = errorMessage != null ? new DistributedMapError("RunFailed", errorMessage, null, null) : null;
        return new DistributedMapSummary(status, true, 5 - failureCount, failureCount, 5, arn, error, 5L, OPERATION);
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
        assertEquals("run blew up", error.getErrorObject().errorMessage());
        assertTrue(error.getMessage().contains("run blew up"));
    }

    @Test
    void throwIfError_exceptionIsADurableOperationExceptionCarryingTheOperationAndRunError() {
        var s = summary(DistributedMapStatus.FAILED, 2, "arn:x", "run blew up");

        var error = assertThrows(DistributedMapException.class, s::throwIfError);
        assertInstanceOf(DurableOperationException.class, error);
        assertSame(OPERATION, error.getOperation());
        assertEquals("RunFailed", error.getErrorObject().errorType());
        assertEquals("run blew up", error.getErrorObject().errorMessage());
        assertEquals(DistributedMapStatus.FAILED, error.status());
        assertEquals(2, error.failureCount());
        assertEquals("Distributed map run FAILED, 2 item(s) failed: run blew up", error.getMessage());
    }

    @Test
    void throwIfError_usesFallbackErrorObjectWhenNoErrorWasRecorded() {
        var s = summary(DistributedMapStatus.FAILED, 0, "arn:x", null);

        var error = assertThrows(DistributedMapException.class, s::throwIfError);
        assertEquals(
                DistributedMapException.class.getName(), error.getErrorObject().errorType());
        assertEquals(error.getMessage(), error.getErrorObject().errorMessage());
        assertNull(error.getErrorObject().errorData());
        assertFalse(error.getErrorObject().hasStackTrace());
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
