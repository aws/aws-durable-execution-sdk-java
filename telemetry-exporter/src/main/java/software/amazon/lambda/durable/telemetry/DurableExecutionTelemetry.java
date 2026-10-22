// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.exception.CallbackException;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.CallbackSubmitterException;
import software.amazon.lambda.durable.exception.CallbackTimeoutException;
import software.amazon.lambda.durable.exception.ChildContextFailedException;
import software.amazon.lambda.durable.exception.DurableExecutionException;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.exception.InvokeException;
import software.amazon.lambda.durable.exception.InvokeFailedException;
import software.amazon.lambda.durable.exception.InvokeStoppedException;
import software.amazon.lambda.durable.exception.InvokeTimedOutException;
import software.amazon.lambda.durable.exception.MapIterationFailedException;
import software.amazon.lambda.durable.exception.NonDeterministicExecutionException;
import software.amazon.lambda.durable.exception.ParallelBranchFailedException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.exception.StepException;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.exception.StepInterruptedException;
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

    /**
     * The SDK's own wrapper types, matched on the exact runtime class. Only these are unwrapped (rule 2). A subclass,
     * including a user subclass of one of them, is not a wrapper. Every class in the SDK exception package is either
     * here or in {@link #SDK_ERRORS}; a test fails when a new class is in neither.
     */
    static final Set<Class<? extends Throwable>> SDK_WRAPPERS = Set.of(
            DurableOperationException.class,
            UnrecoverableDurableExecutionException.class,
            StepException.class,
            CallbackException.class,
            InvokeException.class,
            StepFailedException.class,
            CallbackFailedException.class,
            CallbackSubmitterException.class,
            ChildContextFailedException.class,
            InvokeFailedException.class,
            MapIterationFailedException.class,
            ParallelBranchFailedException.class,
            WaitForConditionFailedException.class);

    /**
     * SDK exceptions that are not wrappers: they name a failure themselves and report their simple class name (rule 5).
     * Kept for the classification test; the walk needs only {@link #SDK_WRAPPERS}.
     */
    static final Set<Class<? extends Throwable>> SDK_ERRORS = Set.of(
            DurableExecutionException.class,
            SerDesException.class,
            CallbackTimeoutException.class,
            InvokeTimedOutException.class,
            InvokeStoppedException.class,
            StepInterruptedException.class,
            IllegalDurableOperationException.class,
            NonDeterministicExecutionException.class);

    /** SDK base classes that never name a failure themselves: with nothing to unwrap they report {@code Error}. */
    private static final Set<Class<?>> SDK_BASE_WRAPPERS = Set.of(
            DurableOperationException.class,
            UnrecoverableDurableExecutionException.class,
            StepException.class,
            CallbackException.class,
            InvokeException.class);

    /** Reported name when a type string is missing or blank (Table A, A12 and A13). */
    static final String DEFAULT_ERROR_NAME = "Error";

    /** Maximum number of unwrap steps (rule 3). */
    static final int MAX_UNWRAPS = 16;

    /**
     * Returns the cross-SDK {@code error.name} of a throwable.
     *
     * <ol>
     *   <li>A throwable that is not an SDK wrapper (see {@link #isSdkWrapper}) reports its own class name, reduced by
     *       {@link #simpleTypeName}. This includes user errors that have a cause, user subclasses of SDK exceptions and
     *       the SDK errors that are not wrappers, such as {@code CallbackTimeoutException}.
     *   <li>A wrapper whose operation timed out (callback or invoke) or was stopped (invoke) reports the SDK error the
     *       core throws for it: {@code CallbackTimeoutException}, {@code InvokeTimedOutException} or
     *       {@code InvokeStoppedException}. These are SDK errors, not wrappers, so live and replayed records agree.
     *   <li>A wrapper with a live cause is unwrapped. At most {@value #MAX_UNWRAPS} unwraps happen; if the error
     *       reached after them is still a wrapper, that wrapper's name is reported. If the cause is an error already
     *       visited, the walk stops and the last error visited before the repeat is reported.
     *   <li>A wrapper's name is its checkpointed {@code errorType}, reduced. The core writes that type as
     *       {@link Class#getName()}, so a replayed failure gets the same name as the live one.
     *   <li>A wrapper whose {@code errorType} is blank reports the SDK failure class the core throws for its operation
     *       (for example {@code CallbackFailedException}), so the operation and the execution agree.
     * </ol>
     */
    static String errorName(Throwable t) {
        return errorName(t, Throwable::getCause);
    }

    /**
     * {@link #errorName(Throwable)} with the cause lookup supplied, so a test can give the walk a cycle: the JDK's
     * {@link Throwable#getCause()} never returns the throwable itself, and the SDK wrappers' causes are fixed at
     * construction.
     */
    static String errorName(Throwable t, UnaryOperator<Throwable> causeOf) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = t;
        for (int unwraps = 0; ; unwraps++) {
            if (!isSdkWrapper(current)) {
                return javaTypeName(current.getClass().getName());
            }
            String sdkError = sdkErrorOfOperation(current);
            if (sdkError != null) {
                return sdkError;
            }
            visited.add(current);
            Throwable cause = causeOf.apply(current);
            if (cause == null || unwraps == MAX_UNWRAPS || visited.contains(cause)) {
                return wrapperName(current);
            }
            current = cause;
        }
    }

    /**
     * {@code true} when the runtime class of {@code t} is exactly one of {@link #SDK_WRAPPERS}. A user subclass of an
     * SDK exception is not a wrapper, and neither is an SDK error such as a callback timeout.
     */
    static boolean isSdkWrapper(Throwable t) {
        return t != null && SDK_WRAPPERS.contains(t.getClass());
    }

    /** Name of an SDK wrapper whose cause is not followed. */
    private static String wrapperName(Throwable wrapper) {
        ErrorObject error = extractErrorObject(wrapper);
        String type = error == null ? null : error.errorType();
        if (!isBlankType(type)) {
            // A callback or invoke error type is supplied from outside the JVM and is never a Java class name.
            return hasExternalErrorType(wrapper) ? simpleTypeName(type) : javaTypeName(type);
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

    /** {@code true} when the wrapper's checkpointed error type was written by a callback or invoke caller. */
    private static boolean hasExternalErrorType(Throwable wrapper) {
        if (!(wrapper instanceof DurableOperationException doe) || doe.getOperation() == null) {
            return false;
        }
        var type = doe.getOperation().type();
        return type == OperationType.CALLBACK || type == OperationType.CHAINED_INVOKE;
    }

    /**
     * The SDK error (not a wrapper) the core throws for a wrapper's operation, whatever its checkpoint says: a
     * timed-out callback, or a timed-out or stopped invoke. {@code null} for any other operation.
     */
    private static String sdkErrorOfOperation(Throwable wrapper) {
        if (!(wrapper instanceof DurableOperationException doe) || doe.getOperation() == null) {
            return null;
        }
        var op = doe.getOperation();
        if (op.type() == OperationType.CALLBACK && op.status() == OperationStatus.TIMED_OUT) {
            return CallbackTimeoutException.class.getSimpleName();
        }
        if (op.type() == OperationType.CHAINED_INVOKE && op.status() == OperationStatus.TIMED_OUT) {
            return InvokeTimedOutException.class.getSimpleName();
        }
        if (op.type() == OperationType.CHAINED_INVOKE && op.status() == OperationStatus.STOPPED) {
            return InvokeStoppedException.class.getSimpleName();
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

    /** The address suffix of a hidden class's {@link Class#getName()}, which changes on every JVM start. */
    private static final Pattern HIDDEN_CLASS_SUFFIX = Pattern.compile("/0x[0-9a-fA-F]+$");

    /**
     * {@link #simpleTypeName} for a Java class name ({@link Class#getName()}), live or as the core checkpointed it.
     * Only a hidden-class suffix matching {@code /0x[0-9a-fA-F]+$} is removed first, so the same failure keeps the same
     * name across cold starts. No other {@code '/'} is touched, and type strings from outside the JVM (callback and
     * invoke error types) never come here.
     */
    static String javaTypeName(String className) {
        if (className == null) {
            return DEFAULT_ERROR_NAME;
        }
        return simpleTypeName(HIDDEN_CLASS_SUFFIX.matcher(className).replaceFirst(""));
    }

    /**
     * Reduces a type string to the reported {@code error.name} (Table A of the cross-SDK rule): the part after the last
     * {@code '.'} or {@code '$'}. If that part is empty the string is kept as it is. A {@code null} string, or one made
     * only of the six ASCII whitespace characters (see {@link #isBlankType}), gives {@code "Error"}. The string is not
     * trimmed.
     */
    static String simpleTypeName(String typeName) {
        if (isBlankType(typeName)) {
            return DEFAULT_ERROR_NAME;
        }
        int cut = Math.max(typeName.lastIndexOf('.'), typeName.lastIndexOf('$'));
        String last = typeName.substring(cut + 1);
        return last.isEmpty() ? typeName : last;
    }

    /**
     * {@code true} for {@code null}, the empty string, or a string made only of space, {@code \t}, {@code \n},
     * {@code \r}, {@code \f} and {@code \u000B} (rule 6). No other character is blank: unlike {@link String#isBlank()},
     * U+001C to U+001F and the Unicode spaces such as U+2003 are not, and U+00A0 and U+FEFF are not either.
     */
    static boolean isBlankType(String type) {
        if (type == null) {
            return true;
        }
        for (int i = 0; i < type.length(); i++) {
            switch (type.charAt(i)) {
                case ' ', '\t', '\n', '\r', '\f', '\u000B' -> {}
                default -> {
                    return false;
                }
            }
        }
        return true;
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
