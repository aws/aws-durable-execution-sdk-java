// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.lambda.durable.model.DistributedMapError;
import software.amazon.lambda.durable.model.DistributedMapStatus;

/**
 * Thrown by throwIfError when a distributed map run did not fully succeed, or when an item failed on a run that
 * otherwise succeeded.
 */
public class DistributedMapException extends DurableOperationException {
    private final DistributedMapStatus status;
    private final long failureCount;

    /** Run outcome failure describing the run status, failure count, and the run's error details. */
    public DistributedMapException(
            Operation operation, DistributedMapStatus status, long failureCount, DistributedMapError error) {
        this(operation, status, failureCount, runMessage(status, failureCount, error), error);
    }

    /** Item failure surfacing the first failed item's error. */
    public DistributedMapException(
            Operation operation,
            DistributedMapStatus status,
            long failureCount,
            DistributedMapError error,
            String itemId) {
        this(operation, status, failureCount, itemMessage(error, itemId), error);
    }

    private DistributedMapException(
            Operation operation,
            DistributedMapStatus status,
            long failureCount,
            String message,
            DistributedMapError error) {
        super(operation, toErrorObject(error, message), message);
        this.status = status;
        this.failureCount = failureCount;
    }

    /** Returns the terminal run status. */
    public DistributedMapStatus status() {
        return status;
    }

    /** Returns the count of failed items. */
    public long failureCount() {
        return failureCount;
    }

    private static ErrorObject toErrorObject(DistributedMapError error, String message) {
        if (error == null) {
            return ErrorObject.builder()
                    .errorType(DistributedMapException.class.getName())
                    .errorMessage(message)
                    .build();
        }
        return ErrorObject.builder()
                .errorType(error.errorType())
                .errorMessage(error.errorMessage())
                .errorData(error.errorData())
                .stackTrace(error.stackTrace())
                .build();
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
