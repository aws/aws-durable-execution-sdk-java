// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.annotations.Experimental;

/**
 * Error detail carried on a record or operation: {@code {name, message, cause?}}, the shape shared by the Telemetry
 * exporters of every SDK. {@code cause} has the same shape and is {@code null} when the error has no cause.
 */
@Experimental
public final class ErrorInfo {
    private final String name;
    private final String message;
    private final ErrorInfo cause;

    public ErrorInfo(String name, String message) {
        this(name, message, null);
    }

    public ErrorInfo(String name, String message, ErrorInfo cause) {
        this.name = name;
        this.message = message;
        this.cause = cause;
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
