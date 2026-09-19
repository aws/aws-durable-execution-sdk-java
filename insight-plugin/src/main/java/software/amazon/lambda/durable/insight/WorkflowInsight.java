// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
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
 * <p>{@link #workflowInsight} returns a {@link DurableExecutionPluginFactory}, so the SDK creates one
 * {@link InsightPlugin} per Lambda invocation and drops it when the invocation returns. Everything about an execution —
 * the stable start time, the parsed ARN, the one-time sampling decision, the detached input snapshot, the queued
 * record, the drain signal — is therefore a plain field of that instance. Nothing is keyed by execution ARN, and there
 * is no per-execution entry to remove at invocation end, so a suspended execution cannot leak one for the lifetime of a
 * warm container. Nothing is lost across a resume either: the resume's own {@link InvocationInfo} carries the same
 * stable start time, the sampling decision is deterministic in the ARN, and the input snapshot is taken again from
 * {@link InvocationInfo#executionInput()}.
 *
 * <p>What belongs to the execution environment rather than to an invocation stays in the factory: the resolved
 * {@link InsightSettings}, the exporters, and the {@link ExportScheduler} that serializes exports across every
 * execution the environment hosts.
 */
@Experimental
public final class WorkflowInsight {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowInsight.class);

    private WorkflowInsight() {}

    /**
     * Creates a Workflow Insight plugin factory from the given config. Mirrors the JS {@code workflowInsight(config)}.
     *
     * <p>The configuration is resolved once, here; the exporters and the scheduler that serializes exports across them
     * are created once, here. The returned factory then builds one plugin instance per invocation, which is what lets
     * that instance hold its execution's state in plain fields.
     *
     * @param config the plugin configuration
     * @return a factory to hand to {@code DurableConfig.Builder.withPlugins}
     */
    public static DurableExecutionPluginFactory workflowInsight(WorkflowInsightConfig config) {
        InsightSettings settings = new InsightSettings(config);
        ExportScheduler scheduler = new ExportScheduler(
                settings.exporters, WorkflowInsight::exportRecord, t -> logSafely("export scheduling failed", t));
        return info -> new InsightPlugin(settings, scheduler, info);
    }

    // --- helpers ---

    /** Shapes and exports one record to one exporter; runs on a scheduler worker, never on an SDK hook thread. */
    static void exportRecord(WorkflowInsightRecord record, InsightExporter exporter) {
        try {
            // Give each exporter its own deep copy: truncation returns the original record when it already fits,
            // so without this a custom exporter that mutates operations or nested content would corrupt every
            // other exporter's view of the same record.
            WorkflowInsightRecord isolated = record.deepCopy();
            WorkflowInsightRecord shaped =
                    Truncation.truncateRecord(isolated, exporter.maxRecordSizeBytes(), exporter::render);
            exporter.export(shaped);
        } catch (Throwable t) {
            // Catch Throwable, not just RuntimeException: deep copy, truncation, an exporter's render/export, or
            // the linkage of an optional exporter class (a NoClassDefFoundError when the S3 / CloudWatch SDK is
            // absent) can each fail with an Error. Isolating every Throwable here guarantees one failing exporter
            // cannot affect the others, nor disrupt the execution.
            logSafely("exporter failed", t);
        }
    }

    /**
     * Applies a user-supplied result transform to an operation's checkpointed (serialized JSON) result. Parses the JSON
     * before handing it to the transform, so the transform always receives a <em>detached, JSON-compatible</em> value
     * (a {@code Map} for a former POJO, a {@code List} for an array, or a scalar such as a {@code String} for a Java
     * time value) — never the SDK's original Java object. The raw string is passed through only when the checkpointed
     * result is not valid JSON. User transforms are untrusted: a throwing transform (any {@link Throwable}) omits the
     * field rather than leaking the raw value or failing the execution, and the failure is logged for diagnosis.
     * Because the value is freshly parsed from the immutable checkpoint string on every build, a transform that mutates
     * its argument cannot corrupt any cached state or a later emission.
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
        } catch (Throwable e) {
            logSafely("operation result transform failed; result omitted", e);
            return null;
        }
    }

    /**
     * Resolves a {@code content.input}/{@code content.output} setting against a value: excluded means omit; a transform
     * is applied (omit and log on throw so a failing redactor never leaks the raw value); otherwise include as-is. When
     * a transform is present it receives a <em>detached, JSON-compatible</em> copy of the value — a POJO becomes a
     * {@code Map}, a Java time type becomes its JSON representation (e.g. an {@code Instant} becomes an ISO-8601
     * {@code String}) — never the SDK's original Java object.
     */
    static Object applyDataContent(String label, Object value, boolean include, Function<Object, Object> transform) {
        if (!include || value == null) {
            return null;
        }
        if (transform != null) {
            try {
                // Hand the transform its own defensive, detached copy: the value may be the cached raw input snapshot
                // reused across multiple emissions (ON_CHANGE), so a transform that mutates its argument in place must
                // not corrupt that snapshot or any later emission's view of it. deepCopyContent also normalizes POJOs
                // to Maps and Java-time types to their JSON representation, so the transform operates on the same
                // JSON-compatible shape the record will emit. Omit and log on any Throwable so a failing redactor never
                // leaks the raw value and never disrupts the execution.
                return transform.apply(Json.deepCopyContent(value));
            } catch (Throwable e) {
                logSafely(label + " transform failed; value omitted", e);
                return null;
            }
        }
        return value;
    }

    /** Logs a plugin failure without ever letting the logging itself disrupt durable execution. */
    static void logSafely(String message, Throwable t) {
        try {
            logger.warn("[workflow-insight] {}", message, t);
        } catch (Throwable ignored) {
            // Never allow a logging failure to propagate into the SDK control flow.
        }
    }

    static ErrorInfo toErrorInfo(Throwable t) {
        // Operation and execution snapshot errors are exposed wrapped: operation failures as DurableOperationException
        // and unrecoverable execution failures as UnrecoverableDurableExecutionException. The wrapper's own
        // class/message would lose the original checkpointed failure identity. When the checkpointed ErrorObject is
        // present (from either wrapper), derive name/message from its errorType/errorMessage, falling back to the
        // throwable's own fields for any value the ErrorObject leaves null.
        ErrorObject error = extractErrorObject(t);
        if (error != null) {
            String name =
                    error.errorType() != null ? error.errorType() : t.getClass().getSimpleName();
            String message = error.errorMessage() != null ? error.errorMessage() : t.getMessage();
            return new ErrorInfo(name, message);
        }
        return new ErrorInfo(t.getClass().getSimpleName(), t.getMessage());
    }

    /**
     * Returns the checkpointed {@link ErrorObject} carried by the SDK's error wrappers, or {@code null} when the
     * throwable is neither wrapper or carries no {@code ErrorObject}. Both {@link DurableOperationException} (operation
     * failures) and {@link UnrecoverableDurableExecutionException} (unrecoverable execution failures) expose the
     * original checkpointed identity via {@code getErrorObject()}.
     */
    private static ErrorObject extractErrorObject(Throwable t) {
        if (t instanceof DurableOperationException doe) {
            return doe.getErrorObject();
        }
        if (t instanceof UnrecoverableDurableExecutionException udee) {
            return udee.getErrorObject();
        }
        return null;
    }

    static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    static String mapStatus(InvocationStatus status) {
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
