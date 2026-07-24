// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Explains why a DAG task was {@link TaskStatus#SKIPPED}.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public enum SkipReason {
    /** The task's {@link TriggerRule} was not satisfied by its upstream dependencies' terminal statuses. */
    TRIGGER_RULE,
    /** The task's {@code runIf} predicate evaluated to {@code false}. */
    RUN_IF_PREDICATE
}
