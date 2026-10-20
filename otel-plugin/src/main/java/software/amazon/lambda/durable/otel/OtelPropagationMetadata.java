// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.api.trace.SpanContext;

/** Pure encoding of an existing operation context into the reviewed X-Ray carrier shape. */
final class OtelPropagationMetadata {
    private OtelPropagationMetadata() {}

    static String fromContext(SpanContext context) {
        if (context == null || !context.isValid()) return null;
        var trace = context.getTraceId();
        var header = "Root=1-" + trace.substring(0, 8) + "-" + trace.substring(8) + ";Parent=" + context.getSpanId()
                + ";Sampled=" + (context.isSampled() ? "1" : "0");
        return header;
    }
}
