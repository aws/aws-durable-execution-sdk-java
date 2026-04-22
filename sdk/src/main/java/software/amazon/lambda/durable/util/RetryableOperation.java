// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.util;

import software.amazon.lambda.durable.DurableContext;

/**
 * A durable operation that can be retried end-to-end by {@link RetryOperationHelper}.
 *
 * <p>Receives the durable context and the 1-based attempt number so callers can generate unique operation names per
 * attempt (e.g., {@code "approval-" + attempt}).
 *
 * @param <T> the result type
 */
@FunctionalInterface
public interface RetryableOperation<T> {

    /**
     * Executes the durable operation.
     *
     * @param context the durable context to use for durable operations
     * @param attempt the current attempt number (1-based: first attempt is 1)
     * @return the operation result
     */
    T execute(DurableContext context, int attempt);
}
