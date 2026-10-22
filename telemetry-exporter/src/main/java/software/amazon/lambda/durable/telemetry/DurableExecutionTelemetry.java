// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.exception.CallbackException;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.CallbackTimeoutException;
import software.amazon.lambda.durable.exception.ChildContextFailedException;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.InvokeException;
import software.amazon.lambda.durable.exception.InvokeFailedException;
import software.amazon.lambda.durable.exception.InvokeStoppedException;
import software.amazon.lambda.durable.exception.InvokeTimedOutException;
import software.amazon.lambda.durable.exception.MapIterationFailedException;
import software.amazon.lambda.durable.exception.ParallelBranchFailedException;
import software.amazon.lambda.durable.exception.StepException;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.exception.WaitForConditionFailedException;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;
import software.amazon.lambda.durable.telemetry.internal.FatalErrors;

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
@Experimental
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
            DurableExecutionTelemetryRecord shaped =
                    Truncation.truncateRecord(isolated, exporter.maxRecordSizeBytes(), exporter::render);
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

    static ErrorInfo toErrorInfo(Throwable t) {
        // Operation and execution snapshot errors are exposed wrapped: operation failures as DurableOperationException
        // and unrecoverable execution failures as UnrecoverableDurableExecutionException. The wrapper's own
        // class/message would lose the original checkpointed failure identity. When the checkpointed ErrorObject is
        // present (from either wrapper), derive the message from its errorMessage, falling back to the throwable's
        // own message when the ErrorObject leaves it null. The name follows errorName.
        ErrorObject error = extractErrorObject(t);
        String message = error != null && error.errorMessage() != null ? error.errorMessage() : t.getMessage();
        return new ErrorInfo(errorName(t), message);
    }

    /** Package that declares the SDK's own exception types; only classes declared here can be SDK wrappers. */
    private static final String SDK_EXCEPTION_PACKAGE = DurableOperationException.class.getPackageName();

    /** SDK base classes that never name a failure themselves: with nothing to unwrap they report {@code Error}. */
    private static final Set<Class<?>> SDK_BASE_WRAPPERS = Set.of(
            DurableOperationException.class,
            UnrecoverableDurableExecutionException.class,
            StepException.class,
            CallbackException.class,
            InvokeException.class);

    /** Reported name when a type string is missing or blank (Table A, A12). */
    static final String DEFAULT_ERROR_NAME = "Error";

    private static final int MAX_WRAPPER_DEPTH = 16;

    /**
     * Returns the cross-SDK {@code error.name} of a throwable.
     *
     * <ol>
     *   <li>A throwable that is not an SDK wrapper (see {@link #isSdkWrapper}) reports its own class name, reduced by
     *       {@link #simpleTypeName}. This includes user errors that have a cause and user subclasses of SDK exceptions.
     *   <li>An SDK wrapper with a live cause is unwrapped, following the chain of SDK wrappers up to a depth of
     *       {@value #MAX_WRAPPER_DEPTH}; the depth bound also stops a cyclic chain.
     *   <li>An SDK wrapper with no cause reports its checkpointed {@code errorType}, reduced. The core writes that type
     *       as {@link Class#getName()}, so a replayed failure gets the same name as the live one.
     *   <li>An SDK wrapper whose {@code errorType} is blank reports the SDK failure class the core throws for its
     *       operation (for example {@code CallbackFailedException}), so the operation and the execution agree.
     *   <li>A timed-out callback is never unwrapped: it reports {@code CallbackTimeoutException}.
     * </ol>
     */
    static String errorName(Throwable t) {
        Throwable current = t;
        for (int depth = 0; ; depth++) {
            if (!isSdkWrapper(current)) {
                return simpleTypeName(current.getClass().getName());
            }
            String timeout = callbackTimeoutName(current);
            if (timeout != null) {
                return timeout;
            }
            Throwable cause = current.getCause();
            if (cause != null && cause != current) {
                if (depth >= MAX_WRAPPER_DEPTH) {
                    return simpleTypeName(cause.getClass().getName());
                }
                current = cause;
                continue;
            }
            return wrapperName(current);
        }
    }

    /**
     * {@code true} when {@code t} is one of the SDK's own wrapper types: an instance of
     * {@link DurableOperationException} or {@link UnrecoverableDurableExecutionException} whose runtime class is
     * declared in the SDK's exception package. A user subclass of an SDK exception is not a wrapper. A callback timeout
     * is an SDK error, not a wrapper.
     */
    static boolean isSdkWrapper(Throwable t) {
        return (t instanceof DurableOperationException || t instanceof UnrecoverableDurableExecutionException)
                && !(t instanceof CallbackTimeoutException)
                && SDK_EXCEPTION_PACKAGE.equals(t.getClass().getPackageName());
    }

    /** Name of an SDK wrapper that has no cause to follow. */
    private static String wrapperName(Throwable wrapper) {
        ErrorObject error = extractErrorObject(wrapper);
        String type = error == null ? null : error.errorType();
        if (type != null && !type.isBlank()) {
            return simpleTypeName(type);
        }
        if (wrapper instanceof DurableOperationException doe && doe.getOperation() != null) {
            var op = doe.getOperation();
            String failure = sdkFailureName(op.typeAsString(), op.subType(), op.status());
            if (failure != null) {
                return failure;
            }
        }
        if (!SDK_BASE_WRAPPERS.contains(wrapper.getClass())) {
            return simpleTypeName(wrapper.getClass().getName());
        }
        return DEFAULT_ERROR_NAME;
    }

    /** {@code CallbackTimeoutException} for a wrapper whose operation is a timed-out callback, else {@code null}. */
    private static String callbackTimeoutName(Throwable wrapper) {
        if (wrapper instanceof DurableOperationException doe
                && doe.getOperation() != null
                && doe.getOperation().type() == OperationType.CALLBACK
                && doe.getOperation().status() == OperationStatus.TIMED_OUT) {
            return CallbackTimeoutException.class.getSimpleName();
        }
        return null;
    }

    /**
     * Returns the simple name of the SDK exception the core throws when an operation of this type, sub-type and status
     * fails with no reconstructable user error, or {@code null} when there is none. Used when the checkpoint carries no
     * error type, so an operation record and the execution error it propagates to get the same name.
     */
    static String sdkFailureName(String type, String subType, OperationStatus status) {
        if (type == null || status == null) {
            return null;
        }
        Class<?> failure = switch (type) {
            case "STEP" ->
                status != OperationStatus.FAILED
                        ? null
                        : OperationSubType.WAIT_FOR_CONDITION.getValue().equals(subType)
                                ? WaitForConditionFailedException.class
                                : StepFailedException.class;
            case "CALLBACK" ->
                switch (status) {
                    case FAILED -> CallbackFailedException.class;
                    case TIMED_OUT -> CallbackTimeoutException.class;
                    default -> null;
                };
            case "CHAINED_INVOKE" ->
                switch (status) {
                    case FAILED -> InvokeFailedException.class;
                    case TIMED_OUT -> InvokeTimedOutException.class;
                    case STOPPED -> InvokeStoppedException.class;
                    default -> null;
                };
            case "CONTEXT" -> status != OperationStatus.FAILED ? null : contextFailure(subType);
            default -> null;
        };
        return failure == null ? null : failure.getSimpleName();
    }

    private static Class<?> contextFailure(String subType) {
        if (OperationSubType.MAP_ITERATION.getValue().equals(subType)) {
            return MapIterationFailedException.class;
        }
        if (OperationSubType.PARALLEL_BRANCH.getValue().equals(subType)) {
            return ParallelBranchFailedException.class;
        }
        if (OperationSubType.RUN_IN_CHILD_CONTEXT.getValue().equals(subType)
                || OperationSubType.WITH_RETRY.getValue().equals(subType)) {
            return ChildContextFailedException.class;
        }
        return null;
    }

    /**
     * Reduces a type string to the reported {@code error.name} (Table A of the cross-SDK rule): the part after the last
     * {@code '.'} or {@code '$'}. If that part is empty the string is kept as it is. A {@code null}, empty or blank
     * string gives {@code "Error"}. The string is not trimmed.
     */
    static String simpleTypeName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return DEFAULT_ERROR_NAME;
        }
        int cut = Math.max(typeName.lastIndexOf('.'), typeName.lastIndexOf('$'));
        String last = typeName.substring(cut + 1);
        return last.isEmpty() ? typeName : last;
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
