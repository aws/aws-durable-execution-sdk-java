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
 * <p>All invocations of the same execution share a single trace ID (derived from the execution ARN). Operations get
 * stable span IDs derived from the execution ARN + operation ID, ensuring the same operation produces the same span
 * across invocations.
 *
 * <p>When no pending operation ID is set, falls back to random generation (standard OTel behavior).
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public class DeterministicIdGenerator implements IdGenerator {

    private static final IdGenerator RANDOM = IdGenerator.random();

    private final AtomicReference<String> executionTraceId = new AtomicReference<>(null);
    private final ThreadLocal<String> pendingSpanOperationId = new ThreadLocal<>();
    private final AtomicReference<String> durableExecutionArn = new AtomicReference<>(null);

    /**
     * Sets the execution ARN used for generating deterministic IDs.
     *
     * @param arn the durable execution ARN
     */
    public void setDurableExecutionArn(String arn) {
        this.durableExecutionArn.set(arn);
        this.executionTraceId.set(generateTraceIdFromArn(arn));
    }

    /**
     * Queues the next span to use a deterministic ID derived from the given operation ID.
     *
     * @param operationId the operation ID to derive the span ID from
     */
    public void setNextSpanOperationId(String operationId) {
        this.pendingSpanOperationId.set(operationId);
    }

    /**
     * Generates a deterministic span ID for a given operation ID without consuming the ThreadLocal state.
     *
     * <p>Used for creating non-recording placeholder spans when a parent operation's span context is needed but hasn't
     * been exported yet.
     *
     * @param operationId the operation ID to derive the span ID from
     * @return a deterministic 16-char hex span ID
     */
    public String generateSpanIdForOperation(String operationId) {
        return generateSpanIdFromOperation(operationId);
    }

    @Override
    public String generateTraceId() {
        var cached = executionTraceId.get();
        if (cached != null) {
            return cached;
        }
        return RANDOM.generateTraceId();
    }

    @Override
    public String generateSpanId() {
        var operationId = pendingSpanOperationId.get();
        if (operationId != null) {
            pendingSpanOperationId.remove();
            return generateSpanIdFromOperation(operationId);
        }
        return RANDOM.generateSpanId();
    }

    /**
     * Generates a deterministic trace ID from an execution ARN.
     *
     * <p>Uses SHA-256 hash truncated to 32 hex chars (128 bits) for the trace ID.
     */
    private String generateTraceIdFromArn(String arn) {
        var hash = sha256(arn);
        // Trace ID is 32 hex chars (16 bytes)
        return hash.substring(0, 32);
    }

    /**
     * Generates a deterministic span ID from the execution ARN + operation ID.
     *
     * <p>Uses SHA-256 hash truncated to 16 hex chars (64 bits) for the span ID.
     */
    private String generateSpanIdFromOperation(String operationId) {
        var arn = durableExecutionArn.get();
        var input = arn != null ? arn + ":" + operationId : operationId;
        var hash = sha256(input);
        // Span ID is 16 hex chars (8 bytes)
        return hash.substring(0, 16);
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
