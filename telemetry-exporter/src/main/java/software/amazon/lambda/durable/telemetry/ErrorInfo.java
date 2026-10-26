// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error detail carried on a record or operation: {@code {name, message, cause?}}, the shape shared by the Telemetry
 * exporters of every SDK. {@code cause} has the same shape and is {@code null} when the error has no cause.
 *
 * <p>{@code name} and {@code message} are each capped at 8,192 characters, counted as Unicode code points: a longer
 * value keeps its first 8,192 code points followed by {@code …}, and a cut never splits a surrogate pair. The cap is
 * applied here, when the error is created, so it holds for the error and every cause. Applying it again leaves the
 * value unchanged.
 */
public final class ErrorInfo {
    /** Longest {@code name} or {@code message} written, in Unicode code points, before the {@code …} marker. */
    static final int MAX_CHARS = 8_192;

    private final String name;
    private final String message;
    private final ErrorInfo cause;

    public ErrorInfo(String name, String message) {
        this(name, message, null);
    }

    public ErrorInfo(String name, String message, ErrorInfo cause) {
        this.name = cap(name);
        this.message = cap(message);
        this.cause = cause;
    }

    /** Caps {@code value} at {@link #MAX_CHARS} code points plus {@code …}; never splits a surrogate pair. */
    static String cap(String value) {
        if (value == null || value.length() <= MAX_CHARS) {
            return value;
        }
        if (value.codePointCount(0, value.length()) <= MAX_CHARS) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_CHARS)) + "\u2026";
    }

    public String name() {
        return name;
    }

    public String message() {
        return message;
    }

    /** The error's cause, or {@code null} when it has none. */
    public ErrorInfo cause() {
        return cause;
    }

    /**
     * Serializes to the wire shape {@code {"name": ..., "message": ..., "cause": ...}}, keys in that order;
     * {@code cause} is omitted when absent.
     */
    public Map<String, Object> toWireMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("message", message);
        if (cause != null) {
            data.put("cause", cause.toWireMap());
        }
        return data;
    }
}
