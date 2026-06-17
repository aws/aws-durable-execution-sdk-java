// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

/**
 * Extracts trace context from the Lambda runtime environment.
 *
 * <p>Implementations read trace context from various sources (X-Ray trace header, W3C traceparent, etc.) and return an
 * {@link ExtractedContext} containing the trace ID and optional parent span ID.
 *
 * <p>Called once per invocation in {@code onInvocationStart} to establish the parent trace context.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
@FunctionalInterface
public interface ContextExtractor {

    /**
     * Extracts trace context from the runtime environment.
     *
     * @return the extracted context, or {@code null} if no context is available
     */
    ExtractedContext extract();
}
