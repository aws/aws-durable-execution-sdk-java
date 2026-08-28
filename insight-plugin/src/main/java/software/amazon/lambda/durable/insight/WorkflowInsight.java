// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.insight.exporters.LambdaLogExporter;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeInfo;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * Workflow Insight instrumentation plugin for the Durable Execution Java SDK.
 *
 * <p>Ports the JS {@code workflowInsight()} emission model to the Java plugin hook surface. Each record is built from
 * the <em>current invocation operation snapshot</em> the SDK hands the plugin — {@link InvocationInfo#operations()} at
 * invocation start / operation change, and {@link InvocationEndInfo#operations()} at invocation end — rather than from
 * per-hook accumulation. Execution input/output flow through {@link InvocationInfo#executionInput()} and
 * {@link InvocationEndInfo#executionResult()}, and per-operation results through
 * {@link OperationChangeItemInfo#result()}; these are the fields PR&nbsp;#618 surfaced on the hook records, so
 * {@code input}, {@code output}, and operation {@code result} are now populated exactly as in the JS plugin.
 *
 * <p>Per-execution state (keyed by execution ARN) holds only the stable start time, the parsed ARN, the cached input,
 * and the one-time sampling decision. State is preserved across non-terminal (PENDING/RETRYING) invocations so
 * suspend/resume keeps a single stable start time, and is removed only once the execution reaches a terminal state.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class WorkflowInsight {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowInsight.class);

    private WorkflowInsight() {}

    /** Creates a Workflow Insight plugin from the given config. Mirrors the JS {@code workflowInsight(config)}. */
    public static DurableExecutionPlugin workflowInsight(WorkflowInsightConfig config) {
        return new InsightPlugin(config);
    }

    /** Per-execution state, keyed by execution ARN, to prevent warm-container bleed and handle resume. */
    private static final class ExecutionState {
        final Instant startTime;
        final ArnParser arn;
        final boolean sampledIn;
        volatile Object cachedInput;

        ExecutionState(Instant startTime, ArnParser arn, boolean sampledIn) {
            this.startTime = startTime;
            this.arn = arn;
            this.sampledIn = sampledIn;
        }
    }

    private static final class InsightPlugin implements DurableExecutionPlugin {
        private final double samplingRate;
        private final WorkflowInsightConfig.EmitMode emitMode;
        private final boolean topLevelOnly;
        private final boolean includeErrors;
        private final ContentConfig content;
        private final Map<String, OperationOverride> overridesByName = new LinkedHashMap<>();
        private final List<InsightExporter> exporters;

        private final Map<String, ExecutionState> byArn = new ConcurrentHashMap<>();

        InsightPlugin(WorkflowInsightConfig config) {
            this.samplingRate = resolveSamplingRate(config.samplingRate());
            this.emitMode = config.emitMode() != null ? config.emitMode() : WorkflowInsightConfig.EmitMode.ON_COMPLETE;
            this.topLevelOnly = config.operationDetail() != WorkflowInsightConfig.OperationDetail.FULL_TREE;
            this.content = config.content();
            this.includeErrors = content == null || content.includeErrors();
            if (content != null) {
                for (OperationOverride o : content.overrides()) {
                    overridesByName.put(o.operationName(), o);
                }
            }
            this.exporters =
                    config.exporters().isEmpty() ? List.of(new LambdaLogExporter()) : List.copyOf(config.exporters());
        }

        private ExecutionState getState(String arn, Instant startTime) {
            return byArn.computeIfAbsent(
                    arn, a -> new ExecutionState(startTime, ArnParser.parse(a), shouldSample(a, samplingRate)));
        }

        @Override
        public void onInvocationStart(InvocationInfo info) {
            ExecutionState state = getState(info.durableExecutionArn(), info.executionStartTime());
            if (!state.sampledIn) {
                return;
            }
            state.cachedInput = info.executionInput();
            if (emitMode == WorkflowInsightConfig.EmitMode.ON_CHANGE) {
                emit(buildRecord(
                        state,
                        info.durableExecutionArn(),
                        "RUNNING",
                        info.operations(),
                        null,
                        info.executionInput(),
                        null,
                        null));
            }
        }

        @Override
        public void onOperationChange(OperationChangeInfo info) {
            if (emitMode != WorkflowInsightConfig.EmitMode.ON_CHANGE) {
                return;
            }
            ExecutionState state = byArn.get(info.durableExecutionArn());
            if (state == null || !state.sampledIn) {
                return;
            }
            emit(buildRecord(
                    state,
                    info.durableExecutionArn(),
                    "RUNNING",
                    info.operations(),
                    null,
                    state.cachedInput,
                    null,
                    null));
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            ExecutionState state = getState(info.durableExecutionArn(), info.executionStartTime());
            String status = mapStatus(info.invocationStatus());
            boolean isTerminal = "SUCCEEDED".equals(status) || "FAILED".equals(status);
            boolean isFailure = "FAILED".equals(status);
            boolean shouldEmit;
            switch (emitMode) {
                case ON_CHANGE:
                    shouldEmit = true;
                    break;
                case ON_FAILURE:
                    shouldEmit = isFailure;
                    break;
                case ON_COMPLETE:
                default:
                    shouldEmit = isTerminal;
                    break;
            }

            if (state.sampledIn && shouldEmit) {
                emit(buildRecord(
                        state,
                        info.durableExecutionArn(),
                        status,
                        info.operations(),
                        Instant.now(),
                        info.executionInput(),
                        info.executionResult(),
                        info.executionError()));
            }

            // Only clear state once the execution is truly finished. onInvocationEnd also fires on non-terminal
            // suspends (PENDING/RETRYING); clearing there would lose the original startTime/cachedInput across resumes.
            if (isTerminal) {
                byArn.remove(info.durableExecutionArn());
            }
        }

        /** Serializes each record to every exporter, isolating failures so one exporter never blocks the others. */
        private void emit(WorkflowInsightRecord record) {
            for (InsightExporter exporter : exporters) {
                try {
                    // Give each exporter its own deep copy: truncation returns the original record when it already
                    // fits, so without this a custom exporter that mutates operations or nested content would corrupt
                    // every exporter that runs after it.
                    WorkflowInsightRecord isolated = record.deepCopy();
                    WorkflowInsightRecord shaped =
                            Truncation.truncateRecord(isolated, exporter.maxRecordSizeBytes(), exporter::render);
                    exporter.export(shaped);
                    exporter.flush();
                } catch (RuntimeException e) {
                    logger.warn("[workflow-insight] exporter failed", e);
                }
            }
        }

        private WorkflowInsightRecord buildRecord(
                ExecutionState state,
                String arn,
                String status,
                Map<String, OperationChangeItemInfo> operations,
                Instant endTime,
                Object input,
                Object output,
                Throwable error) {
            WorkflowInsightRecord record = new WorkflowInsightRecord();
            ArnParser a = state.arn;
            record.emittedAt = Instant.now().toString();
            record.executionArn = arn;
            record.executionName = emptyToNull(a.executionName());
            record.functionName = a.functionName();
            record.functionQualifier = a.qualifier();
            record.region = a.region();
            record.accountId = a.accountId();
            record.status = status;
            record.startTime = state.startTime != null ? state.startTime.toString() : null;
            if (endTime != null) {
                record.endTime = endTime.toString();
                if (state.startTime != null) {
                    record.durationMs = endTime.toEpochMilli() - state.startTime.toEpochMilli();
                }
            }
            record.input = applyDataContent(
                    input,
                    content == null || content.includeInput(),
                    content == null ? null : content.inputTransform());
            record.output = applyDataContent(
                    output,
                    content == null || content.includeOutput(),
                    content == null ? null : content.outputTransform());
            if (error != null) {
                record.error = toErrorInfo(error);
            }
            record.operations = buildOperationRecords(operations);
            return record;
        }

        private List<OperationRecord> buildOperationRecords(Map<String, OperationChangeItemInfo> operations) {
            List<OperationRecord> out = new ArrayList<>();
            if (operations == null) {
                return out;
            }
            // The hook contract supplies a map with no iteration-order guarantee (the core snapshot originates from a
            // concurrent map). Sort by startTimestamp ascending (null timestamps last), then by a stable operation id
            // tie-breaker, so the emitted operations array is deterministic and OperationsIndex's "latest occurrence"
            // scalar fields reflect true chronological order rather than arbitrary map iteration order.
            List<OperationChangeItemInfo> items = new ArrayList<>(operations.values());
            items.sort(Comparator.comparing(
                            OperationChangeItemInfo::startTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(OperationChangeItemInfo::id, Comparator.nullsLast(Comparator.naturalOrder())));
            for (OperationChangeItemInfo item : items) {
                // The SDK core tracks the invocation/execution itself as a pseudo-entry of type EXECUTION; it is not a
                // customer operation and the record already carries the execution status/timing at top level.
                if ("EXECUTION".equals(item.type())) {
                    continue;
                }
                // Unnamed operations can't be targeted or keyed — excluded by default (matches JS `if (!op.name)`).
                if (item.name() == null) {
                    continue;
                }
                // top-level detail drops anything nested under a context (parallel branches, map items, nested steps).
                if (topLevelOnly && item.parentId() != null) {
                    continue;
                }
                OperationOverride override = overridesByName.get(item.name());
                if (override != null && override.isExclude()) {
                    continue;
                }
                OperationRecord rec = new OperationRecord()
                        .id(item.id())
                        .name(item.name())
                        .type(item.type())
                        .subType(item.subType())
                        .parentId(item.parentId())
                        .status(item.status() != null ? item.status().toString() : "UNKNOWN")
                        .startTime(
                                item.startTimestamp() != null
                                        ? item.startTimestamp().toString()
                                        : null)
                        .endTime(
                                item.endTimestamp() != null
                                        ? item.endTimestamp().toString()
                                        : null)
                        .attempt(item.attempt());
                if (item.startTimestamp() != null && item.endTimestamp() != null) {
                    rec.durationMs(item.endTimestamp().toEpochMilli()
                            - item.startTimestamp().toEpochMilli());
                }
                if (includeErrors && item.error() != null) {
                    rec.error(toErrorInfo(item.error()));
                }
                // Results are omitted unless an override explicitly opts in via a transform (matches JS).
                if (override != null && override.result() != null) {
                    rec.result(applyResultOverride(override.result(), item.result()));
                }
                out.add(rec);
            }
            return out;
        }
    }

    // --- helpers ---

    /**
     * Applies a user-supplied result transform to an operation's checkpointed (serialized JSON) result. Parses the JSON
     * before handing it to the transform, falling back to the raw string when it isn't valid JSON. User transforms are
     * untrusted: a throwing transform omits the field rather than leaking the raw value or failing the execution.
     */
    static Object applyResultOverride(Function<Object, Object> transform, String rawResult) {
        if (rawResult == null) {
            return null;
        }
        Object parsed;
        try {
            parsed = Json.MAPPER.readValue(rawResult, Object.class);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            parsed = rawResult;
        }
        try {
            return transform.apply(parsed);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Resolves a {@code content.input}/{@code content.output} setting against a value: excluded means omit; a transform
     * is applied (omit on throw so a failing redactor never leaks the raw value); otherwise include as-is.
     */
    static Object applyDataContent(Object value, boolean include, Function<Object, Object> transform) {
        if (!include || value == null) {
            return null;
        }
        if (transform != null) {
            try {
                return transform.apply(value);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return value;
    }

    private static ErrorInfo toErrorInfo(Throwable t) {
        // Operation and execution snapshot errors are exposed as DurableOperationException wrappers, so the wrapper's
        // own class/message would lose the original checkpointed failure identity. When the checkpointed ErrorObject is
        // present, derive name/message from its errorType/errorMessage, falling back to the throwable's own fields for
        // any value the ErrorObject leaves null.
        if (t instanceof DurableOperationException doe && doe.getErrorObject() != null) {
            ErrorObject error = doe.getErrorObject();
            String name =
                    error.errorType() != null ? error.errorType() : t.getClass().getSimpleName();
            String message = error.errorMessage() != null ? error.errorMessage() : t.getMessage();
            return new ErrorInfo(name, message);
        }
        return new ErrorInfo(t.getClass().getSimpleName(), t.getMessage());
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String mapStatus(InvocationStatus status) {
        if (status == InvocationStatus.SUCCEEDED) {
            return "SUCCEEDED";
        }
        if (status == InvocationStatus.FAILED) {
            return "FAILED";
        }
        // PENDING / RETRYING are still in flight from the execution's point of view.
        return "RUNNING";
    }

    /** FNV-1a 32-bit hash, identical to the JS implementation (Java int multiply wraps mod 2^32 like Math.imul). */
    static int fnv1a32(String input) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < input.length(); i++) {
            hash ^= input.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }

    static boolean shouldSample(String executionArn, double rate) {
        if (rate >= 1) {
            return true;
        }
        if (rate <= 0) {
            return false;
        }
        long unsigned = fnv1a32(executionArn) & 0xffffffffL;
        return (double) unsigned / 0xffffffffL < rate;
    }

    static double resolveSamplingRate(Double rate) {
        if (rate == null) {
            return 1;
        }
        if (Double.isNaN(rate)) {
            return 1;
        }
        if (rate < 0 || rate > 1) {
            return Math.max(0, Math.min(1, rate));
        }
        return rate;
    }
}
