// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Thrown by {@code DagResult.throwIfError()} when the DAG completed with at least one failed task. Wraps the first
 * failed task's cause (when available).
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public class DagExecutionException extends DagException {
    public DagExecutionException(String message) {
        super(message);
    }

    public DagExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
