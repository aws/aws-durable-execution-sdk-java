// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import java.util.Optional;

/**
 * Per-task snapshot passed to a DAG custom completion predicate.
 *
 * @param name the task name
 * @param status the task's status; {@link Optional#empty()} if the task has not started
 * @param result present only when {@code status} is {@link TaskStatus#SUCCEEDED}
 * @param skipReason present only when {@code status} is {@link TaskStatus#SKIPPED}
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public record DagCompletionItemStatus(
        String name, Optional<TaskStatus> status, Optional<Object> result, Optional<SkipReason> skipReason) {}
