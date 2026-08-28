// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error detail carried on a record or operation, mirroring the JS {@code {name, message}} shape.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class ErrorInfo {
    private final String name;
    private final String message;

    public ErrorInfo(String name, String message) {
        this.name = name;
        this.message = message;
    }

    public String name() {
        return name;
    }

    public String message() {
        return message;
    }

    /** Serializes to the camelCase wire shape {@code {"name": ..., "message": ...}}. */
    public Map<String, Object> toWireMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("message", message);
        return data;
    }
}
