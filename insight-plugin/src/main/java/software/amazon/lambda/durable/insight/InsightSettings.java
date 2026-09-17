// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.insight.exporters.LambdaLogExporter;

/**
 * The plugin's configuration, resolved once and then immutable: everything a record's shape depends on that does not
 * depend on which invocation is being observed.
 *
 * <p>This belongs to the execution environment, not to an invocation. {@link WorkflowInsight#workflowInsight} resolves
 * it once and the factory it returns hands the same instance to every {@link InsightPlugin} it creates, so resolving
 * defaults, validating the sampling rate and indexing the operation overrides happen once per environment rather than
 * once per invocation.
 */
final class InsightSettings {

    /** Sampling rate, clamped to [0, 1]; the per-invocation decision is derived from it and the execution ARN. */
    final double samplingRate;

    final WorkflowInsightConfig.EmitMode emitMode;

    /** True when nested operations (parallel branches, map items, nested steps) are dropped from the record. */
    final boolean topLevelOnly;

    final boolean includeErrors;

    /** May be null, which means "every default": include input, output and errors, with no transforms. */
    final ContentConfig content;

    /** Operation overrides indexed by operation name, in declaration order. */
    final Map<String, OperationOverride> overridesByName;

    /** The configured exporters, or the default single {@link LambdaLogExporter} when none were configured. */
    final List<InsightExporter> exporters;

    InsightSettings(WorkflowInsightConfig config) {
        this.samplingRate = WorkflowInsight.resolveSamplingRate(config.samplingRate());
        this.emitMode = config.emitMode() != null ? config.emitMode() : WorkflowInsightConfig.EmitMode.ON_COMPLETE;
        this.topLevelOnly = config.operationDetail() != WorkflowInsightConfig.OperationDetail.FULL_TREE;
        this.content = config.content();
        this.includeErrors = content == null || content.includeErrors();
        Map<String, OperationOverride> overrides = new LinkedHashMap<>();
        if (content != null) {
            for (OperationOverride override : content.overrides()) {
                overrides.put(override.operationName(), override);
            }
        }
        // Unmodifiable wrapper rather than Map.copyOf: declaration order is preserved and an override with a null
        // operation name is tolerated exactly as the mutable map tolerated it.
        this.overridesByName = Collections.unmodifiableMap(overrides);
        this.exporters =
                config.exporters().isEmpty() ? List.of(new LambdaLogExporter()) : List.copyOf(config.exporters());
    }
}
