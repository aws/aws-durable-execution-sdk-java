// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.exception.DurableExecutionException;

/**
 * Base exception for DAG operations. DAG failures are extension-level errors rather than failures associated with one
 * primitive operation.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public class DagException extends DurableExecutionException {
    public DagException(String message) {
        super(message);
    }

    public DagException(String message, Throwable cause) {
        super(message, cause);
    }
}
