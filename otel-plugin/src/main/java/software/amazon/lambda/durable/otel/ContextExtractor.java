// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

/**
 * Extracts the durable execution's propagated trace context from the Lambda runtime environment.
 *
 * <p>Implementations read trace context from various sources (X-Ray trace header, W3C traceparent, etc.) and return an
 * {@link ExtractedContext} containing the trace ID and optional parent span ID.
 *
 * <p><strong>When it is called:</strong> the plugin invokes {@link #extract()} once at the start of every invocation,
 * unconditionally — including when an ambient OpenTelemetry span is active. The extracted context is the durable
 * execution's identity and is resolved with the following precedence:
 *
 * <ol>
 *   <li>a valid extracted backend context anchors the execution trace (this is what makes the durable spans share one
 *       stable trace across reinvocations, so it takes precedence over the per-invocation ambient span);
 *   <li>otherwise the execution is anchored on a deterministic synthetic root derived from the execution ARN;
 *   <li>the ambient span is never adopted as the execution trace. It is used only to parent the Invocation span when it
 *       is already on the resolved trace, and otherwise correlated with a span link.
 * </ol>
 *
 * <p><strong>Implementation contract:</strong> because {@code extract()} runs on every invocation, implementations must
 * be side-effect-free (or idempotent) and cheap, and must return the durable execution's own context — returning stale
 * or unrelated context will displace the correct execution trace.
 */
@FunctionalInterface
public interface ContextExtractor {

    /**
     * Extracts the durable execution's propagated trace context from the runtime environment. Called once per
     * invocation, unconditionally.
     *
     * @return the extracted context, or {@code null} if no context is available
     */
    ExtractedContext extract();
}
