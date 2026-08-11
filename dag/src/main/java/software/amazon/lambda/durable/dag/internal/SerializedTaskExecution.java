// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import software.amazon.lambda.durable.dag.DagTaskError;
import software.amazon.lambda.durable.dag.SkipReason;
import software.amazon.lambda.durable.dag.TaskStatus;

/**
 * JSON-safe serialized form of a {@link software.amazon.lambda.durable.dag.TaskExecution}. Internal.
 *
 * <p>Field order matches the cross-language envelope task shape for console readability: {@code name}, {@code status},
 * {@code skipReason}, {@code resultKind}, {@code result}, {@code error}, {@code startedAt}, {@code completedAt}.
 *
 * <p>There is deliberately no {@code resultType} field: a {@code PLAIN} result is rehydrated on replay from the task's
 * <em>declared</em> result type (recovered by task name from the registered graph — see {@link DagResultTypes}), not
 * from a class name persisted in the checkpoint. That both removes the field from the customer-facing payload and
 * eliminates any {@code Class.forName} on a checkpoint-supplied string.
 *
 * @param name the task name
 * @param status the terminal status
 * @param skipReason the skip reason, or {@code null}
 * @param resultKind how {@code result} must be rehydrated ({@code plain} | {@code batch} | {@code dag})
 * @param result the (kind-tagged) result payload, or {@code null}
 * @param error the failure error, or {@code null}
 * @param startedAt ISO-8601 UTC start time, or {@code null}
 * @param completedAt ISO-8601 UTC completion time, or {@code null}
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
