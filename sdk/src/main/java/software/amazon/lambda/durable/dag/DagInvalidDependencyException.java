// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Thrown at registration when a task declares a dependency on a handle that was not registered in the same DAG scope.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public class DagInvalidDependencyException extends DagException {
    public DagInvalidDependencyException(String message) {
        super(message);
    }
}
