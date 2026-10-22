// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry.internal;

import software.amazon.lambda.durable.telemetry.DurableExecutionTelemetryRecord;

/**
 * Internal, not part of the supported API: implemented by built-in exporters that can measure their rendered size
 * without building the rendered value. Truncation uses it instead of serializing {@code render(record)} on every size
 * check.
 *
 * <p>The rendering must be the record's JSON (either wire shape), or that JSON embedded as one JSON string value. That
 * lets truncation bound how much one dropped result or operation can shrink the output and skip size checks that cannot
 * succeed yet.
 */
public interface RenderedSize {

    /**
     * The exact UTF-8 size of {@code Json.stringify(render(record))}, or {@code null} when the record cannot be
     * serialized.
     */
    Integer renderedSize(DurableExecutionTelemetryRecord record);

    /**
     * The UTF-8 size {@code text} takes when it is written as the content of a JSON string (quotes excluded), using the
     * serializer's escaping: {@code "} and {@code \} and the control characters {@code \b \t \n \f \r} take 2 bytes,
     * other control characters below U+0020 take 6 ({@code \}{@code u00XX}), and everything else is written as UTF-8 (a
     * surrogate pair is 4 bytes; a lone surrogate is replaced by {@code ?}, 1 byte).
     */
    static long escapedUtf8Size(CharSequence text) {
        long size = 0;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                if (c == '"' || c == '\\' || c == '\b' || c == '\t' || c == '\n' || c == '\f' || c == '\r') {
                    size += 2;
                } else if (c < 0x20) {
                    size += 6;
                } else {
                    size += 1;
                }
            } else if (c < 0x800) {
                size += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                size += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                size += 1;
            } else {
                size += 3;
            }
        }
        return size;
    }
}
