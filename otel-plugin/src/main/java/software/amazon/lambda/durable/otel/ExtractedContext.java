// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

/**
 * Trace context extracted from the Lambda runtime environment.
 *
 * <p>Contains the trace ID (always present) and an optional parent span ID used to parent an Invocation span to ambient
 * Lambda/X-Ray context.
 *
 * @param traceId 32-character lowercase hex trace ID (OTel format, no dashes)
 * @param parentSpanId 16-character lowercase hex parent span ID (may be null if no parent available)
 */
public record ExtractedContext(String traceId, String parentSpanId) {}
