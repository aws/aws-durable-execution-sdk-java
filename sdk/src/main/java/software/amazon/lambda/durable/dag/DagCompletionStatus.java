// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Progress snapshot passed to a DAG custom completion predicate.
 *
 * @param successCount tasks that have succeeded so far
 * @param failureCount tasks that have failed so far
 * @param skippedCount tasks that have been skipped so far
 * @param completedCount successCount + failureCount + skippedCount (all terminal states)
 * @param totalCount total number of tasks registered in the DAG
 * @param items per-task snapshot, ordered by registration order
 * @param results terminal task snapshots keyed by task name
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public record DagCompletionStatus(
        int successCount,
        int failureCount,
        int skippedCount,
        int completedCount,
        int totalCount,
        List<DagCompletionItemStatus> items,
        Map<String, DagCompletionItemStatus> results) {}
