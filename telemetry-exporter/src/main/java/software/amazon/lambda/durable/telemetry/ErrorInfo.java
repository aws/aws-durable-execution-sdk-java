// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Error detail carried on a record or operation: {@code {name, message, cause?}}, the shape shared by the Telemetry
 * exporters of every SDK. {@code cause} has the same shape and is {@code null} when the error has no cause.
 *
 * <p>{@code message} is capped at {@link #MAX_MESSAGE_CHARS} characters (Unicode code points): a longer message keeps
 * its first {@value #MAX_MESSAGE_CHARS} characters followed by {@code …}. The cap is applied here, when the error is
 * created, so it holds for the error and every cause. Applying it again leaves the message unchanged.
 */
@Experimental
public final class ErrorInfo {
    /** Longest {@code message} written, in characters (Unicode code points), before the {@code …} marker. */
    public static final int MAX_MESSAGE_CHARS = 8_192;

    private final String name;
    private final String message;
    private final ErrorInfo cause;

    public ErrorInfo(String name, String message) {
        this(name, message, null);
    }

    public ErrorInfo(String name, String message, ErrorInfo cause) {
        this.name = name;
        this.message = capMessage(message);
        this.cause = cause;
    }

    /** Caps {@code message} at {@link #MAX_MESSAGE_CHARS} code points plus {@code …}; never splits a surrogate pair. */
    static String capMessage(String message) {
        if (message == null || message.length() <= MAX_MESSAGE_CHARS) {
            return message;
        }
        if (message.codePointCount(0, message.length()) <= MAX_MESSAGE_CHARS) {
            return message;
        }
        return message.substring(0, message.offsetByCodePoints(0, MAX_MESSAGE_CHARS)) + "\u2026";
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
