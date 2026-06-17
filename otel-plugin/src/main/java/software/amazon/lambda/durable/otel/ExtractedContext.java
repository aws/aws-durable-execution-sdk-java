// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

/**
 * Trace context extracted from the Lambda runtime environment.
 *
 * <p>Contains the trace ID (always present) and an optional parent span ID. When the durable execution backend
 * propagates the same X-Ray Root across all invocations, the trace ID will be consistent, enabling spans from different
 * invocations to be stitched into a single trace.
 *
 * @param traceId 32-character lowercase hex trace ID (OTel format, no dashes)
 * @param parentSpanId 16-character lowercase hex parent span ID (may be null if no parent available)
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public record ExtractedContext(String traceId, String parentSpanId) {}
