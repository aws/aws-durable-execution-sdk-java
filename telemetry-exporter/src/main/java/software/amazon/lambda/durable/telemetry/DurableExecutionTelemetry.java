// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;
import software.amazon.lambda.durable.telemetry.internal.FatalErrors;
import software.amazon.lambda.durable.telemetry.internal.RenderedSize;

/**
 * Durable Execution Telemetry instrumentation plugin for the Durable Execution Java SDK.
 *
 * <p>Ports the JS {@code durableExecutionTelemetry()} emission model to the Java plugin hook surface. Each record is
 * built from the <em>current invocation operation snapshot</em> the SDK hands the plugin —
 * {@link InvocationInfo#operations()} at invocation start / operation change, and
 * {@link InvocationEndInfo#operations()} at invocation end — rather than from per-hook accumulation. Execution
 * input/output flow through {@link InvocationInfo#executionInput()} and {@link InvocationEndInfo#executionResult()},
 * and per-operation results through {@link OperationChangeItemInfo#result()}; these are the fields PR&nbsp;#618
 * surfaced on the hook records, so {@code input}, {@code output}, and operation {@code result} are now populated
 * exactly as in the JS plugin.
 *
 * <p>{@link #durableExecutionTelemetry} returns a {@link DurableExecutionPluginFactory}, so the SDK creates one
 * {@link TelemetryPlugin} per Lambda invocation and drops it when the invocation returns. Everything about an execution
 * — the stable start time, the parsed ARN, the one-time sampling decision, the detached input snapshot, the queued
 * record, the drain signal — is therefore a plain field of that instance. Nothing is keyed by execution ARN, and there
 * is no per-execution entry to remove at invocation end, so a suspended execution cannot leak one for the lifetime of a
 * warm container. Nothing is lost across a resume either: the resume's own {@link InvocationInfo} carries the same
 * stable start time, the sampling decision is deterministic in the ARN, and the input snapshot is taken again from
 * {@link InvocationInfo#executionInput()}.
 *
 * <p>What belongs to the execution environment rather than to an invocation stays in the factory: the resolved
 * {@link TelemetrySettings}, the exporters, and the {@link ExportScheduler} that serializes exports across every
 * execution the environment hosts.
 */
public final class DurableExecutionTelemetry {

    private static final Logger logger = LoggerFactory.getLogger(DurableExecutionTelemetry.class);

    private DurableExecutionTelemetry() {}

    /**
     * Creates a Durable Execution Telemetry plugin factory from the given config. Mirrors the JS
     * {@code durableExecutionTelemetry(config)}.
     *
     * <p>The configuration is resolved once, here; the exporters and the scheduler that serializes exports across them
     * are created once, here. The returned factory then builds one plugin instance per invocation, which is what lets
     * that instance hold its execution's state in plain fields.
     *
     * @param config the plugin configuration
     * @return a factory to hand to {@code DurableConfig.Builder.withPlugins}
     */
    public static DurableExecutionPluginFactory durableExecutionTelemetry(DurableExecutionTelemetryConfig config) {
        TelemetrySettings settings = new TelemetrySettings(config);
        ExportScheduler scheduler = new ExportScheduler(
                settings.exporters,
                DurableExecutionTelemetry::exportRecord,
                t -> logSafely("export scheduling failed", t));
        return info -> new TelemetryPlugin(settings, scheduler, info);
    }

    // --- helpers ---

    /** Shapes and exports one record to one exporter; runs on a scheduler worker, never on an SDK hook thread. */
    static void exportRecord(DurableExecutionTelemetryRecord record, TelemetryExporter exporter) {
        try {
            // Give each exporter its own deep copy: truncation returns the original record when it already fits,
            // so without this a custom exporter that mutates operations or nested content would corrupt every
            // other exporter's view of the same record.
            DurableExecutionTelemetryRecord isolated = record.deepCopy();
            DurableExecutionTelemetryRecord shaped = exporter instanceof RenderedSize measured
                    ? Truncation.truncateMeasured(isolated, exporter.maxRecordSizeBytes(), measured::renderedSize)
                    : Truncation.truncateRecord(isolated, exporter.maxRecordSizeBytes(), exporter::render);
            exporter.export(shaped);
        } catch (Throwable t) {
            // Ordinary Errors (such as missing optional dependencies) are isolated too.
            // logSafely rethrows VM/thread termination before logging or continuing.
            logSafely("exporter failed", t);
        }
    }

    /**
     * Applies a user-supplied result transform to an operation's checkpointed (serialized JSON) result. Parses the JSON
     * before handing it to the transform, so the transform always receives a <em>detached, JSON-compatible</em> value
     * (a {@code Map} for a former POJO, a {@code List} for an array, or a scalar such as a {@code String} for a Java
     * time value) — never the SDK's original Java object. The raw string is passed through only when the checkpointed
     * result is not valid JSON. Non-fatal transform failures omit the field rather than leaking the raw value and are
     * logged for diagnosis. VM/thread-termination errors propagate. Because the value is freshly parsed from the
     * immutable checkpoint string on every build, a transform that mutates its argument cannot corrupt any cached state
     * or a later emission.
     */
    static Object applyResultOverride(Function<Object, Object> transform, String rawResult) {
        if (rawResult == null) {
            return null;
        }
        Object parsed;
        try {
            parsed = Json.MAPPER.readValue(rawResult, Object.class);
        } catch (RuntimeException | JsonProcessingException e) {
            FatalErrors.rethrow(e);
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
                // JSON-compatible shape the record will emit. Ordinary failures omit the raw value;
                // fatal VM/thread termination is rethrown by logSafely.
                return transform.apply(Json.deepCopyContent(value));
            } catch (Throwable e) {
                logSafely(label + " transform failed; value omitted", e);
                return null;
            }
        }
        return value;
    }

    /** Rethrows fatal failures; contains and logs ordinary plugin/logging failures. */
    static void logSafely(String message, Throwable t) {
        FatalErrors.rethrow(t);
        try {
            logger.warn("[durable-execution-telemetry] {}", message, t);
        } catch (Throwable ignored) {
            FatalErrors.rethrow(ignored);
            // Ordinary logging failures stay isolated from SDK control flow.
        }
    }

    /** The cause chain written under an error is cut after this many levels. */
    static final int MAX_CAUSE_DEPTH = 16;

    static ErrorInfo toErrorInfo(Throwable t) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(t);
        return toErrorInfo(t, visited, 0);
    }

    /**
     * Converts {@code t} and, recursively, its {@link Throwable#getCause()} chain. Name and message come from the
     * checkpointed {@link ErrorObject} when the SDK wrapper carries one; the chain still follows {@code getCause()}, so
     * a live cause behind such a wrapper (for example a checkpoint continuation failure) is kept. The chain is cut
     * after {@link #MAX_CAUSE_DEPTH} levels and stops at a cause already visited (by identity), so a cycle cannot loop.
     * A non-fatal failure while reading a cause's name or message ends the chain at that cause. For the root error
     * ({@code depth == 0}) such a failure does not drop the error: the message is written as {@code null} and the name
     * falls back to the class's simple name.
     */
    private static ErrorInfo toErrorInfo(Throwable t, Set<Throwable> visited, int depth) {
        ErrorInfo cause = null;
        Throwable next = readCause(t);
        if (next != null && depth < MAX_CAUSE_DEPTH && visited.add(next)) {
            try {
                cause = toErrorInfo(next, visited, depth + 1);
            } catch (Throwable failure) {
                FatalErrors.rethrow(failure);
                cause = null; // A cause whose name or message cannot be read ends the chain.
            }
        }
        return depth == 0 ? rootNameAndMessage(t, cause) : nameAndMessage(t, cause);
    }

    /** {@link #nameAndMessage} for the root error: an accessor that throws yields the class name and a null message. */
    private static ErrorInfo rootNameAndMessage(Throwable t, ErrorInfo cause) {
        try {
            return nameAndMessage(t, cause);
        } catch (Throwable failure) {
            FatalErrors.rethrow(failure);
        }
        ErrorObject error = null;
        try {
            error = extractErrorObject(t);
        } catch (Throwable failure) {
            FatalErrors.rethrow(failure);
        }
        String name = null;
        String message = null;
        if (error != null) {
            try {
                name = error.errorType();
            } catch (Throwable failure) {
                FatalErrors.rethrow(failure);
            }
            try {
                message = error.errorMessage();
            } catch (Throwable failure) {
                FatalErrors.rethrow(failure);
            }
        }
        if (message == null) {
            try {
                message = t.getMessage();
            } catch (Throwable failure) {
                FatalErrors.rethrow(failure);
            }
        }
        return new ErrorInfo(name != null ? name : t.getClass().getSimpleName(), message, cause);
    }

    /** {@code t.getCause()}, or {@code null} when the accessor fails with a non-fatal error. */
    private static Throwable readCause(Throwable t) {
        try {
            return t.getCause();
        } catch (Throwable failure) {
            FatalErrors.rethrow(failure);
            return null;
        }
    }

    private static ErrorInfo nameAndMessage(Throwable t, ErrorInfo cause) {
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
            return new ErrorInfo(name, message, cause);
        }
        return new ErrorInfo(t.getClass().getSimpleName(), t.getMessage(), cause);
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
