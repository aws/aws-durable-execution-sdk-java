// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Produces the payload for a DAG invoke task from resolved upstream results ({@link Deps}).
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
@FunctionalInterface
public interface DagPayloadFunction {
    Object apply(Deps deps);
}
