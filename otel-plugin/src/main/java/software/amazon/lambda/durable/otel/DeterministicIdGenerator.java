// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * Generates deterministic trace and span IDs for durable execution observability.
 *
 * <p>The durable plugins use short-lived ID overrides around their own {@link SpanBuilder#startSpan()} calls. Outside
 * those scopes, generation delegates to the fallback generator so unrelated instrumentation keeps normal root trace ID
 * generation. Scoped values are also bridged through thread-keyed system properties because the application plugin and
 * Java-agent extension may load this class in different class loaders.
 *
 * <p>The existing setter methods remain available for callers that use this class directly. Plugin code does not use
 * that persistent mode.
 */
public class DeterministicIdGenerator implements IdGenerator {

    private static final String PROPERTY_PREFIX = "software.amazon.lambda.durable.otel.";
    private static final String SCOPED_TRACE_ID_PROPERTY_PREFIX = PROPERTY_PREFIX + "scopedTraceId.";
    private static final String SCOPED_SPAN_ID_PROPERTY_PREFIX = PROPERTY_PREFIX + "scopedSpanId.";

    private final IdGenerator fallbackIdGenerator;
    private final ThreadLocal<String> extractedTraceId = new ThreadLocal<>();
    private final ThreadLocal<String> arnDerivedTraceId = new ThreadLocal<>();
    private final ThreadLocal<String> pendingSpanOperationId = new ThreadLocal<>();
    private final ThreadLocal<String> pendingRawSpanId = new ThreadLocal<>();
    private final ThreadLocal<IdOverride> scopedIds = new ThreadLocal<>();
    private final ThreadLocal<String> durableExecutionArn = new ThreadLocal<>();

    /** Creates a generator that delegates non-overridden IDs to OpenTelemetry's random generator. */
    public DeterministicIdGenerator() {
        this(IdGenerator.random());
    }

    DeterministicIdGenerator(IdGenerator fallbackIdGenerator) {
        this.fallbackIdGenerator = fallbackIdGenerator;
    }

    // The SDK builder exposes only a setter. Read its configured generator so unrelated spans retain the caller's
    // existing ID policy while durable spans use short-lived overrides.
    static DeterministicIdGenerator installOn(SdkTracerProviderBuilder builder) {
        var currentGenerator = configuredIdGenerator(builder);
        if (currentGenerator instanceof DeterministicIdGenerator deterministicIdGenerator) {
            return deterministicIdGenerator;
        }
        var deterministicIdGenerator = new DeterministicIdGenerator(currentGenerator);
        builder.setIdGenerator(deterministicIdGenerator);
        return deterministicIdGenerator;
    }

    /**
     * Sets an externally extracted trace ID (e.g., from the X-Ray trace header). This takes highest priority for trace
     * ID generation.
     *
     * @param traceId 32-char lowercase hex trace ID
     */
    public void setExtractedTraceId(String traceId) {
        setOrRemove(extractedTraceId, traceId);
    }

    /**
     * Sets the execution ARN used for generating deterministic IDs. Computes and caches an ARN-derived trace ID as
     * fallback when no extracted trace ID is available.
     *
     * @param arn the durable execution ARN
     */
    public void setDurableExecutionArn(String arn) {
        setOrRemove(durableExecutionArn, arn);
        setOrRemove(arnDerivedTraceId, arn != null ? generateTraceIdFromArn(arn) : null);
    }

    /**
     * Queues the next span to use a deterministic ID derived from the given operation ID.
     *
     * @param operationId the operation ID to derive the span ID from
     */
    public void setNextSpanOperationId(String operationId) {
        setOrRemove(pendingSpanOperationId, operationId);
    }

    /**
     * Queues the next span to use the given pre-computed span ID verbatim. Unlike {@link #setNextSpanOperationId}, the
     * supplied value is used directly rather than derived from an operation ID. Used for the Workflow root span, whose
     * ID is derived once from the execution ARN via {@link #generateWorkflowSpanId()}.
     *
     * @param spanId a 16-char lowercase hex span ID
     */
    public void setNextSpanId(String spanId) {
        setOrRemove(pendingRawSpanId, spanId);
    }

    /**
     * Generates a deterministic span ID for a given operation ID without consuming the ThreadLocal state.
     *
     * @param operationId the operation ID to derive the span ID from
     * @return a deterministic 16-char hex span ID
     */
    public String generateSpanIdForOperation(String operationId) {
        return generateSpanIdFromOperation(operationId);
    }

    /**
     * Generates the deterministic span ID for the Workflow root span from the current execution ARN, using the seed
     * {@code "workflow:" + arn} (SHA-256, truncated to 16 hex chars). Stable across all invocations of the same
     * execution so the Workflow span is exported once as a single logical span. Guarded to never be all-zero (an
     * invalid OTel span ID).
     *
     * @return a deterministic 16-char hex span ID
     */
    public String generateWorkflowSpanId() {
        return generateWorkflowSpanId(durableExecutionArn.get());
    }

    String generateTraceIdForExecution(String arn, Instant executionStartTime) {
        var timestamp = executionStartTime != null ? executionStartTime : Instant.now();
        var timestampHex = String.format("%08x", timestamp.getEpochSecond() & 0xffffffffL);
        return timestampHex + sha256(arn != null ? arn : "").substring(0, 24);
    }

    String generateWorkflowSpanId(String arn) {
        var seed = "workflow:" + (arn != null ? arn : "");
        var spanId = sha256(seed).substring(0, 16);
        if (spanId.equals("0000000000000000")) {
            spanId = "0000000000000001";
        }
        return spanId;
    }

    String generateSpanIdForOperation(String arn, String operationId) {
        return generateSpanIdFromOperation(arn, operationId);
    }

    Span startSpan(SpanBuilder spanBuilder, String traceId, String spanId) {
        try (var ignored = useIds(traceId, spanId)) {
            return spanBuilder.startSpan();
        }
    }

    IdScope useIds(String traceId, String spanId) {
        var previousOverride = scopedIds.get();
        var previousTraceId = System.getProperty(scopedTraceIdProperty());
        var previousSpanId = System.getProperty(scopedSpanIdProperty());

        scopedIds.set(new IdOverride(traceId, spanId));
        setOrClearProperty(scopedTraceIdProperty(), traceId);
        setOrClearProperty(scopedSpanIdProperty(), spanId);

        return new IdScope() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                if (previousOverride == null) {
                    scopedIds.remove();
                } else {
                    scopedIds.set(previousOverride);
                }
                setOrClearProperty(scopedTraceIdProperty(), previousTraceId);
                setOrClearProperty(scopedSpanIdProperty(), previousSpanId);
            }
        };
    }

    @Override
    public String generateTraceId() {
        var override = currentOverride();
        if (override != null && override.traceId() != null) {
            return override.traceId();
        }

        // Priority 1: extracted from X-Ray header (backend propagates same Root across invocations)
        var extracted = extractedTraceId.get();
        if (extracted != null) {
            return extracted;
        }
        // Priority 2: deterministic from execution ARN (local tests, non-Lambda)
        var arnDerived = arnDerivedTraceId.get();
        if (arnDerived != null) {
            return arnDerived;
        }
        // Priority 3: random fallback
        return fallbackIdGenerator.generateTraceId();
    }

    @Override
    public String generateSpanId() {
        var override = currentOverride();
        if (override != null && override.spanId() != null) {
            consumeScopedSpanId(override);
            return override.spanId();
        }

        var raw = pendingRawSpanId.get();
        if (raw != null) {
            pendingRawSpanId.remove();
            return raw;
        }
        var operationId = pendingSpanOperationId.get();
        if (operationId != null) {
            pendingSpanOperationId.remove();
            return generateSpanIdFromOperation(operationId);
        }
        return fallbackIdGenerator.generateSpanId();
    }

    @Override
    public boolean generatesRandomTraceIds() {
        var override = currentOverride();
        if (override != null && override.traceId() != null) {
            return false;
        }
        if (extractedTraceId.get() != null || arnDerivedTraceId.get() != null) {
            return false;
        }
        return fallbackIdGenerator.generatesRandomTraceIds();
    }

    /** Generates a deterministic trace ID from an execution ARN using SHA-256 truncated to 32 hex chars. */
    private String generateTraceIdFromArn(String arn) {
        var hash = sha256(arn);
        return hash.substring(0, 32);
    }

    /**
     * Generates a deterministic span ID from the execution ARN + operation ID using SHA-256 truncated to 16 hex chars.
     */
    private String generateSpanIdFromOperation(String operationId) {
        var arn = durableExecutionArn.get();
        return generateSpanIdFromOperation(arn, operationId);
    }

    private String generateSpanIdFromOperation(String arn, String operationId) {
        var input = arn != null ? arn + ":" + operationId : operationId;
        var hash = sha256(input);
        return hash.substring(0, 16);
    }

    private IdOverride currentOverride() {
        var override = scopedIds.get();
        if (override != null) {
            return override;
        }
        var traceId = System.getProperty(scopedTraceIdProperty());
        var spanId = System.getProperty(scopedSpanIdProperty());
        return traceId != null || spanId != null ? new IdOverride(traceId, spanId) : null;
    }

    private static IdGenerator configuredIdGenerator(SdkTracerProviderBuilder builder) {
        for (var field : builder.getClass().getDeclaredFields()) {
            if (!IdGenerator.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                if (!field.trySetAccessible()) {
                    break;
                }
                return (IdGenerator) field.get(builder);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Unable to read the configured OpenTelemetry ID generator", e);
            }
        }
        throw new IllegalStateException("Unable to locate the configured OpenTelemetry ID generator");
    }

    private void consumeScopedSpanId(IdOverride override) {
        if (scopedIds.get() != null) {
            scopedIds.set(new IdOverride(override.traceId(), null));
        }
        System.clearProperty(scopedSpanIdProperty());
    }

    private static String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static void clearSharedStateForTest() {
        System.getProperties().stringPropertyNames().stream()
                .filter(name -> name.startsWith(SCOPED_TRACE_ID_PROPERTY_PREFIX)
                        || name.startsWith(SCOPED_SPAN_ID_PROPERTY_PREFIX))
                .toList()
                .forEach(System::clearProperty);
    }

    private static String scopedTraceIdProperty() {
        return SCOPED_TRACE_ID_PROPERTY_PREFIX + Thread.currentThread().getId();
    }

    private static String scopedSpanIdProperty() {
        return SCOPED_SPAN_ID_PROPERTY_PREFIX + Thread.currentThread().getId();
    }

    private static void setOrClearProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void setOrRemove(ThreadLocal<String> target, String value) {
        if (value == null) {
            target.remove();
        } else {
            target.set(value);
        }
    }

    private record IdOverride(String traceId, String spanId) {}

    interface IdScope extends AutoCloseable {
        @Override
        void close();
    }
}
