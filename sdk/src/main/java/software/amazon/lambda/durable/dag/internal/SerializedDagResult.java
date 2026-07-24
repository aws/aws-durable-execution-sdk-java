// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import java.util.List;
import software.amazon.lambda.durable.dag.DagCompletionReason;

/**
 * JSON-safe serialized form of a {@link software.amazon.lambda.durable.dag.DagResult}. Internal.
 *
 * @param tasks the serialized task executions, in registration order
 * @param completionReason why the DAG finished
 * @param totalCount number of registered tasks (spec §2.8)
 */
public record SerializedDagResult(
        List<SerializedTaskExecution> tasks, DagCompletionReason completionReason, int totalCount) {}
