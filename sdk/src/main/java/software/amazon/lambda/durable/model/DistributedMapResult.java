// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import java.util.Collections;
import java.util.List;
import software.amazon.lambda.durable.exception.DistributedMapException;

/**
 * Outcome of a distributed map run with collected per-item results.
 *
 * <p>An item whose status this SDK version does not recognize is kept in {@code items} with status
 * {@link Item.Status#UNKNOWN_TO_SDK_VERSION}. It is excluded from {@link #succeeded()}, {@link #failed()},
 * {@link #results()} and {@link #errors()}.
 */
public record DistributedMapResult<O>(DistributedMapSummary summary, List<Item<O>> items) {

    /** Applies a defensive copy and defaults. */
    public DistributedMapResult {
        items = items != null ? List.copyOf(items) : Collections.emptyList();
    }

    /** Returns items that succeeded. */
    public List<Item<O>> succeeded() {
        return items.stream()
                .filter(item -> item.status() == Item.Status.SUCCEEDED)
                .toList();
    }

    /** Returns items that failed. */
    public List<Item<O>> failed() {
        return items.stream()
                .filter(item -> item.status() == Item.Status.FAILED)
                .toList();
    }

    /** Returns the outputs of succeeded items (includes null outputs from successful items). */
    public List<O> results() {
        return succeeded().stream().map(Item::output).toList();
    }

    /** Returns the errors of failed items (includes nulls for items that failed without error details). */
    public List<DistributedMapError> errors() {
        return failed().stream().map(Item::error).toList();
    }

    /** Returns the terminal run status. */
    public DistributedMapStatus status() {
        return summary.status();
    }

    /** Returns true only when the service read the complete source, or null before run creation. */
    public Boolean sourceExhausted() {
        return summary.sourceExhausted();
    }

    /** Returns the count of succeeded items. */
    public long successCount() {
        return summary.successCount();
    }

    /** Returns the count of failed items. */
    public long failureCount() {
        return summary.failureCount();
    }

    /**
     * Returns the number of items the run read from its source. For an inline source, every admitted item counts as
     * read when the run starts.
     */
    public long readCount() {
        return summary.readCount();
    }

    /** Returns the total item count, or null until known. */
    public Long totalCount() {
        return summary.totalCount();
    }

    /** Returns the run ARN, or null when the run never started. */
    public String distributedMapRunArn() {
        return summary.distributedMapRunArn();
    }

    /** Returns why the run did not succeed, or null when the service recorded no error details. */
    public DistributedMapError error() {
        return summary.error();
    }

    /** Returns true when any item failed. */
    public boolean hasFailure() {
        return summary.hasFailure();
    }

    /**
     * Throws DistributedMapException when the run did not succeed or any item failed. When the run succeeded and the
     * result contains a failed item, the exception names the first failed item.
     */
    public void throwIfError() {
        if (summary.status() != DistributedMapStatus.SUCCEEDED) {
            summary.throwIfError();
            return;
        }
        var failedItems = failed();
        if (!failedItems.isEmpty()) {
            var first = failedItems.get(0);
            throw new DistributedMapException(summary.status(), summary.failureCount(), first.error(), first.itemId());
        }
        if (summary.hasFailure()) {
            summary.throwIfError();
        }
    }

    /** Outcome of a single distributed map item. */
    public record Item<O>(String itemId, Status status, O output, DistributedMapError error) {

        /** Status of an individual distributed map item. */
        public enum Status {
            SUCCEEDED,
            FAILED,
            /** A status the backend returned that this SDK version does not recognize. */
            UNKNOWN_TO_SDK_VERSION
        }

        /** Creates a succeeded item. */
        public static <O> Item<O> succeeded(String itemId, O output) {
            return new Item<>(itemId, Status.SUCCEEDED, output, null);
        }

        /** Creates a failed item. */
        public static <O> Item<O> failed(String itemId, DistributedMapError error) {
            return new Item<>(itemId, Status.FAILED, null, error);
        }

        /** Creates an item whose status this SDK version does not recognize. */
        public static <O> Item<O> unknown(String itemId) {
            return new Item<>(itemId, Status.UNKNOWN_TO_SDK_VERSION, null, null);
        }
    }
}
