// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import java.util.Collections;
import java.util.List;

/**
 * Exception thrown when a concurrency operation (parallel or map) fails to meet its completion criteria. Contains
 * counts of successful and failed items, plus the individual item failures.
 */
public class ConcurrencyExecutionException extends DurableExecutionException {
    private final int successCount;
    private final int failureCount;
    private final int totalItems;
    private final List<Throwable> itemFailures;

    public ConcurrencyExecutionException(
            int successCount, int failureCount, int totalItems, List<Throwable> itemFailures) {
        super(formatMessage(successCount, failureCount, totalItems));
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.totalItems = totalItems;
        this.itemFailures = itemFailures != null ? Collections.unmodifiableList(itemFailures) : Collections.emptyList();
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public int getTotalItems() {
        return totalItems;
    }

    public List<Throwable> getItemFailures() {
        return itemFailures;
    }

    private static String formatMessage(int successCount, int failureCount, int totalItems) {
        return String.format(
                "Concurrency operation failed: %d/%d items succeeded, %d failed",
                successCount, totalItems, failureCount);
    }
}
