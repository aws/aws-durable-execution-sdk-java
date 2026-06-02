// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.context.Context;

/**
 * Extracts OTel trace context from the Lambda runtime environment.
 *
 * <p>Implementations read trace context from various sources (X-Ray trace header, W3C traceparent, etc.) and return an
 * OTel {@link Context} that can be used as the parent for invocation spans.
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
     * @return the extracted OTel context, or {@link Context#root()} if no context is available
     */
    Context extract();
}
