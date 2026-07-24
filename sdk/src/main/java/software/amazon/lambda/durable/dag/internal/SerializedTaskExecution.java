// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import software.amazon.lambda.durable.dag.DagTaskError;
import software.amazon.lambda.durable.dag.SkipReason;
import software.amazon.lambda.durable.dag.TaskStatus;

/**
 * JSON-safe serialized form of a {@link software.amazon.lambda.durable.dag.TaskExecution}. Internal.
 *
 * @param name the task name
 * @param status the terminal status
 * @param skipReason the skip reason, or {@code null}
 * @param resultKind how {@code result} must be rehydrated
 * @param result the (kind-tagged) result payload, or {@code null}
 * @param error the failure error, or {@code null}
 * @param startedAt ISO-8601 start time, or {@code null}
 * @param completedAt ISO-8601 completion time, or {@code null}
 */
public record SerializedTaskExecution(
        String name,
        TaskStatus status,
        SkipReason skipReason,
        SerializedResultKind resultKind,
        Object result,
        DagTaskError error,
        String startedAt,
        String completedAt) {}
