// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

/**
 * Thrown when {@code get()} is called on a parallel branch that the checkpointed parallel result recorded as
 * {@code SKIPPED}.
 *
 * <p>This happens when a completion config such as {@code firstSuccessful()} finishes the parallel operation before
 * every branch has started, or while a branch is still running. The SDK does not start or resume that branch later, so
 * {@code get()} throws this exception on the first invocation and on every replay.
 */
public class ParallelBranchSkippedException extends DurableExecutionException {
    private final String branchName;

    public ParallelBranchSkippedException(String branchName) {
        super(formatMessage(branchName));
        this.branchName = branchName;
    }

    /** Returns the name of the skipped branch. */
    public String getBranchName() {
        return branchName;
    }

    private static String formatMessage(String branchName) {
        if (branchName == null) {
            return "Parallel branch was skipped";
        }
        return String.format("Parallel branch '%s' was skipped", branchName);
    }
}
