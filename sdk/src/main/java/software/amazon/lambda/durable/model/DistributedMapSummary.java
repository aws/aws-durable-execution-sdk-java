// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import software.amazon.lambda.durable.exception.DistributedMapException;

/**
 * Outcome of a distributed map run, without per-item results.
 *
 * @param sourceExhausted true only when the service read the complete source, null before run creation
 * @param readCount number of items the run read from its source; for an inline source, every admitted item counts as
 *     read when the run starts
 * @param error why the run did not succeed, when the service recorded error details
 */
public record DistributedMapSummary(
        DistributedMapStatus status,
        Boolean sourceExhausted,
        long successCount,
        long failureCount,
        long readCount,
        String distributedMapRunArn,
        DistributedMapError error,
        Long totalCount) {

    /** Returns true when any item failed. */
    public boolean hasFailure() {
        return failureCount > 0;
    }

    /** Throws DistributedMapException when the run did not fully succeed. */
    public void throwIfError() {
        if (status != DistributedMapStatus.SUCCEEDED || hasFailure()) {
            throw new DistributedMapException(status, failureCount, error);
        }
    }
}
