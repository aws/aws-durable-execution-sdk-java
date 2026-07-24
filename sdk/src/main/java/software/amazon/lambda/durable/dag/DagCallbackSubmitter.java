// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * A DAG callback submitter: receives resolved upstream results ({@link Deps}), the generated callback ID, and a
 * {@link StepContext}. Mirrors the native {@code BiConsumer<String, StepContext>} submitter shape plus {@link Deps}.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
@FunctionalInterface
public interface DagCallbackSubmitter {
    void apply(Deps deps, String callbackId, StepContext ctx);
}
