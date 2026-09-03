// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload.filesystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A field selector used by {@link PreviewConfig}.
 *
 * @param name a literal field name for {@link FieldMatchMode#ANYWHERE}, or a dot-separated path for
 *     {@link FieldMatchMode#PATH}; in paths, backslash escapes the following character
 * @param match how the selector is matched
 */
public record PreviewField(String name, FieldMatchMode match) {
    public PreviewField {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(match, "match cannot be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        if (match == FieldMatchMode.PATH) {
            parsePath(name);
        }
    }

    /** Creates a selector that matches this field name at any depth. */
    public PreviewField(String name) {
        this(name, FieldMatchMode.ANYWHERE);
    }

    /** Creates a selector that matches this field name at any depth. */
    public static PreviewField anywhere(String name) {
        return new PreviewField(name, FieldMatchMode.ANYWHERE);
    }

    /**
     * Creates a selector that matches an exact dot-separated path.
     *
     * <p>Use {@code \.} to match a literal dot in a field name and {@code \\} to match a literal backslash.
     */
    public static PreviewField path(String name) {
        return new PreviewField(name, FieldMatchMode.PATH);
    }

    List<String> pathSegments() {
        return parsePath(name);
    }

    private static List<String> parsePath(String path) {
        var segments = new ArrayList<String>();
        var segment = new StringBuilder();
        var escaped = false;
        for (int index = 0; index < path.length(); index++) {
            var character = path.charAt(index);
            if (escaped) {
                segment.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '.') {
                segments.add(segment.toString());
                segment.setLength(0);
            } else {
                segment.append(character);
            }
        }
        if (escaped) {
            throw new IllegalArgumentException("path cannot end with an unescaped backslash");
        }
        segments.add(segment.toString());
        return List.copyOf(segments);
    }
}
