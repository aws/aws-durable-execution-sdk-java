// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

/**
 * Summary result of a parallel operation.
 *
 * <p>Captures the aggregate outcome of a parallel execution: how many branches were registered, how many succeeded, how
 * many failed, and why the operation completed.
 */
public record ParallelResult(int size, int succeeded, int failed, ConcurrencyCompletionStatus completionStatus) {}
