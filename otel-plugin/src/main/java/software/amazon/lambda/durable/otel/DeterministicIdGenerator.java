// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.sdk.trace.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Generates deterministic trace and span IDs for durable execution observability.
 *
 * <p>Trace ID resolution order:
 *
 * <ol>
 *   <li>If an extracted trace ID is set (from {@code _X_AMZN_TRACE_ID}), use it. The durable execution backend
 *       propagates the same Root to all invocations, so this naturally unifies the trace.
 *   <li>If no extracted trace ID is available (local tests, non-Lambda environments), derive a deterministic trace ID
 *       from the execution ARN using SHA-256.
 *   <li>If neither is set, fall back to random generation.
 * </ol>
 *
 * <p>Span IDs for operations are deterministic (derived from execution ARN + operation ID), ensuring the same operation
 * produces the same span across invocations. When no pending operation ID is set, falls back to random generation.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public class DeterministicIdGenerator implements IdGenerator {

    private static final IdGenerator RANDOM = IdGenerator.random();
    private static final String PROPERTY_PREFIX = "software.amazon.lambda.durable.otel.";
    private static final String EXTRACTED_TRACE_ID_PROPERTY = PROPERTY_PREFIX + "extractedTraceId";
    private static final String DURABLE_EXECUTION_ARN_PROPERTY = PROPERTY_PREFIX + "durableExecutionArn";
    private static final String PENDING_SPAN_OPERATION_ID_PROPERTY_PREFIX = PROPERTY_PREFIX + "pendingSpanOperationId.";

    private final AtomicReference<String> extractedTraceId = new AtomicReference<>(null);
    private final AtomicReference<String> arnDerivedTraceId = new AtomicReference<>(null);
    private final ThreadLocal<String> pendingSpanOperationId = new ThreadLocal<>();
    private final AtomicReference<String> durableExecutionArn = new AtomicReference<>(null);

    /**
     * Sets an externally extracted trace ID (e.g., from the X-Ray trace header). This takes highest priority for trace
     * ID generation.
     *
     * @param traceId 32-char lowercase hex trace ID
     */
    public void setExtractedTraceId(String traceId) {
        this.extractedTraceId.set(traceId);
        setOrClearProperty(EXTRACTED_TRACE_ID_PROPERTY, traceId);
    }

    /**
     * Sets the execution ARN used for generating deterministic IDs. Computes and caches an ARN-derived trace ID as
     * fallback when no extracted trace ID is available.
     *
     * @param arn the durable execution ARN
     */
    public void setDurableExecutionArn(String arn) {
        this.durableExecutionArn.set(arn);
        this.arnDerivedTraceId.set(arn != null ? generateTraceIdFromArn(arn) : null);
        setOrClearProperty(DURABLE_EXECUTION_ARN_PROPERTY, arn);
    }

    /**
     * Queues the next span to use a deterministic ID derived from the given operation ID.
     *
     * @param operationId the operation ID to derive the span ID from
     */
    public void setNextSpanOperationId(String operationId) {
        this.pendingSpanOperationId.set(operationId);
        setOrClearProperty(pendingSpanOperationIdProperty(), operationId);
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

    @Override
    public String generateTraceId() {
        // Priority 1: extracted from X-Ray header (backend propagates same Root across invocations)
        var extracted = extractedTraceId.get();
        if (extracted == null) {
            extracted = System.getProperty(EXTRACTED_TRACE_ID_PROPERTY);
        }
        if (extracted != null) {
            return extracted;
        }
        // Priority 2: deterministic from execution ARN (local tests, non-Lambda)
        var arnDerived = arnDerivedTraceId.get();
        if (arnDerived == null) {
            var arn = System.getProperty(DURABLE_EXECUTION_ARN_PROPERTY);
            arnDerived = arn != null ? generateTraceIdFromArn(arn) : null;
        }
        if (arnDerived != null) {
            return arnDerived;
        }
        // Priority 3: random fallback
        return RANDOM.generateTraceId();
    }

    @Override
    public String generateSpanId() {
        var operationId = pendingSpanOperationId.get();
        if (operationId == null) {
            operationId = System.getProperty(pendingSpanOperationIdProperty());
        }
        if (operationId != null) {
            pendingSpanOperationId.remove();
            System.clearProperty(pendingSpanOperationIdProperty());
            return generateSpanIdFromOperation(operationId);
        }
        return RANDOM.generateSpanId();
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
        if (arn == null) {
            arn = System.getProperty(DURABLE_EXECUTION_ARN_PROPERTY);
        }
        var input = arn != null ? arn + ":" + operationId : operationId;
        var hash = sha256(input);
        return hash.substring(0, 16);
    }

    static void clearSharedStateForTest() {
        System.clearProperty(EXTRACTED_TRACE_ID_PROPERTY);
        System.clearProperty(DURABLE_EXECUTION_ARN_PROPERTY);
        System.getProperties().stringPropertyNames().stream()
                .filter(name -> name.startsWith(PENDING_SPAN_OPERATION_ID_PROPERTY_PREFIX))
                .toList()
                .forEach(System::clearProperty);
    }

    private static String pendingSpanOperationIdProperty() {
        return PENDING_SPAN_OPERATION_ID_PROPERTY_PREFIX
                + Thread.currentThread().getId();
    }

    private static void setOrClearProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
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
}
