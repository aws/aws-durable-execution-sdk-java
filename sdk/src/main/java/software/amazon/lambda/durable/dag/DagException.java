// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.exception.DurableOperationException;

/**
 * Base exception for DAG operations. Extends the SDK's {@code DurableOperationException} (→
 * {@code DurableExecutionException} → {@code RuntimeException}) so DAG failures integrate with the existing exception
 * hierarchy. Registration-time validation subtypes carry no underlying {@code Operation}.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public class DagException extends DurableOperationException {
    public DagException(String message) {
        super(null, null, message);
    }

    public DagException(String message, Throwable cause) {
        super(null, null, message, cause);
    }
}
