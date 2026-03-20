// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

/**
 * Summary result of a parallel operation.
 *
 * <p>Captures the aggregate outcome of a parallel execution: how many branches were registered, how many succeeded, how
 * many failed, and why the operation completed.
 */
public class ParallelResult {

    private final int totalBranches;
    private final int succeededBranches;
    private final int failedBranches;
    private final ConcurrencyCompletionStatus completionStatus;

    public ParallelResult(
            int totalBranches,
            int succeededBranches,
            int failedBranches,
            ConcurrencyCompletionStatus completionStatus) {
        this.totalBranches = totalBranches;
        this.succeededBranches = succeededBranches;
        this.failedBranches = failedBranches;
        this.completionStatus = completionStatus;
    }

    /** Returns the total number of branches registered before {@code join()} was called. */
    public int getTotalBranches() {
        return totalBranches;
    }

    /** Returns the number of branches that completed without throwing. */
    public int getSucceededBranches() {
        return succeededBranches;
    }

    /** Returns the number of branches that threw an exception. */
    public int getFailedBranches() {
        return failedBranches;
    }

    /** Returns the status indicating why the parallel operation completed. */
    public ConcurrencyCompletionStatus getCompletionStatus() {
        return completionStatus;
    }
}
