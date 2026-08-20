// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

/**
 * Extracts trace context from the Lambda runtime environment.
 *
 * <p>Implementations read trace context from various sources (X-Ray trace header, W3C traceparent, etc.) and return an
 * {@link ExtractedContext} containing the trace ID and optional parent span ID.
 *
 * <p>Plugins use a valid ambient OpenTelemetry span as the invocation parent when one is available. This extractor is
 * consulted only when no ambient span context is available, providing fallback propagation context from the runtime
 * environment.
 */
@FunctionalInterface
public interface ContextExtractor {

    /**
     * Extracts fallback trace context from the runtime environment.
     *
     * @return the extracted context, or {@code null} if no context is available
     */
    ExtractedContext extract();
}
