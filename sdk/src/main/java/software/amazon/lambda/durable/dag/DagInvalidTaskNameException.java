// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Thrown at registration when a task name violates the DAG charset rules ({@code ^[a-zA-Z0-9_]+$}, {@code <= 100}
 * chars, must not contain the reserved sequence {@code DAG_NODE_T_}).
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public class DagInvalidTaskNameException extends DagException {
    public DagInvalidTaskNameException(String message) {
        super(message);
    }
}
