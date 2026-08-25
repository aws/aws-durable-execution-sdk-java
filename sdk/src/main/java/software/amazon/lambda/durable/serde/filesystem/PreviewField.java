// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.filesystem;

import java.util.Objects;

/**
 * A field selector used by {@link PreviewConfig}.
 *
 * @param name a field name for {@link FieldMatchMode#ANYWHERE}, or a dot-separated path for {@link FieldMatchMode#PATH}
 * @param match how the selector is matched
 */
public record PreviewField(String name, FieldMatchMode match) {
    public PreviewField {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(match, "match cannot be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
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

    /** Creates a selector that matches an exact dot-separated path. */
    public static PreviewField path(String name) {
        return new PreviewField(name, FieldMatchMode.PATH);
    }
}
