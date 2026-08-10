// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.config.CompletionConfig;

/**
 * Threshold-based DAG completion, wrapping the base SDK's {@link CompletionConfig}.
 *
 * @param completionConfig the underlying threshold configuration
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public record ThresholdDagCompletion(CompletionConfig completionConfig) implements DagCompletionConfig {}
