// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.lambda.durable.model.DistributedMapError;
import software.amazon.lambda.durable.model.DistributedMapStatus;

/**
 * Thrown by throwIfError when a distributed map run did not fully succeed, or when an item failed on a run that
 * otherwise succeeded. It reports an aggregate run result and is not a single operation failure, so it stays out of the
 * DurableOperationException hierarchy.
 */
public class DistributedMapException extends DurableExecutionException {
    private final DistributedMapStatus status;
    private final DistributedMapError error;
    private final long failureCount;

    /** Run outcome failure describing the run status, failure count, and the run's error details. */
    public DistributedMapException(DistributedMapStatus status, long failureCount, DistributedMapError error) {
        super(runMessage(status, failureCount, error));
        this.status = status;
        this.error = error;
        this.failureCount = failureCount;
    }

    /** Item failure surfacing the first failed item's error. */
    public DistributedMapException(
            DistributedMapStatus status, long failureCount, DistributedMapError error, String itemId) {
        super(itemMessage(error, itemId));
        this.status = status;
        this.error = error;
        this.failureCount = failureCount;
    }

    /** Returns the terminal run status. */
    public DistributedMapStatus status() {
        return status;
    }

    /** Returns the error details, or null when none were recorded. */
    public DistributedMapError error() {
        return error;
    }

    /** Returns the count of failed items. */
    public long failureCount() {
        return failureCount;
    }

    private static String runMessage(DistributedMapStatus status, long failureCount, DistributedMapError error) {
        var message = new StringBuilder("Distributed map run ").append(status);
        if (failureCount > 0) {
            message.append(", ").append(failureCount).append(" item(s) failed");
        }
        if (error != null && error.errorMessage() != null) {
            message.append(": ").append(error.errorMessage());
        }
        return message.toString();
    }

    private static String itemMessage(DistributedMapError error, String itemId) {
        var type = error != null ? error.errorType() : null;
        var message = error != null ? error.errorMessage() : null;
        var hasType = type != null && !type.isEmpty();
        var hasMessage = message != null && !message.isEmpty();
        var result = new StringBuilder("Distributed map item ").append(itemId).append(" failed");
        if (hasType) {
            result.append(": ").append(type);
        }
        if (hasMessage) {
            result.append(": ").append(message);
        }
        return result.toString();
    }
}
