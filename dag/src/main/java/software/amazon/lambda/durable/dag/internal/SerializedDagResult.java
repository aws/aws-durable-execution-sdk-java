// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import software.amazon.lambda.durable.dag.DagCompletionReason;

/**
 * JSON-safe serialized form of a {@link software.amazon.lambda.durable.dag.DagResult} — the single cross-language DAG
 * container envelope written identically by all four SDKs, for both the inline and the offloaded case. Internal.
 *
 * <p>Field order matches the cross-language envelope for console readability. The aggregate fields ({@code type},
 * counts, {@code completionReason}, {@code startedTaskNames}) are ALWAYS present. Two fields are droppable by the
 * degradation ladder and are therefore omitted (not emitted as {@code null}) when dropped:
 *
 * <ul>
 *   <li>{@code tasks} — its absence is the signal that per-task detail was too large and lives in the retained child
 *       operations instead (the offloaded case). There is no flag field: absence is the signal.
 *   <li>{@code failedTaskNames} — dropped as the last space-saving step before offload; still bounded/recoverable.
 * </ul>
 *
 * <p>Evolution is additive-only (there is no {@code schemaVersion}), so this reader ignores unknown fields
 * ({@link JsonIgnoreProperties}) and treats a missing field as absent rather than failing.
 *
 * @param type the envelope discriminator; always {@code "DagResult"}
 * @param totalCount number of registered tasks (fixed; independent of early completion)
 * @param successCount number of SUCCEEDED tasks
 * @param failureCount number of FAILED tasks
 * @param skippedCount number of SKIPPED tasks
 * @param completionReason why the DAG finished
 * @param startedTaskNames names of tasks started but not terminal at early completion (bounded by
 *     {@code maxConcurrency}); empty on a full drain
 * @param failedTaskNames names of FAILED tasks; omitted (null) only as the last degradation step before offload
 * @param tasks the serialized task executions in registration order, or {@code null} (omitted) when offloaded
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SerializedDagResult(
        String type,
        int totalCount,
        int successCount,
        int failureCount,
        int skippedCount,
        DagCompletionReason completionReason,
        List<String> startedTaskNames,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> failedTaskNames,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<SerializedTaskExecution> tasks) {

    /** The canonical {@code type} discriminator value for a DAG container envelope. */
    public static final String TYPE = "DagResult";
}
