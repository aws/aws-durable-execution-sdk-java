// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.filesystem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for {@link SerDesPreview#buildPreview(Object, PreviewConfig)}.
 *
 * @param mode whether fields are included or excluded by default
 * @param include fields made visible in {@link PreviewMode#EXCLUDE_ALL} mode
 * @param exclude fields hidden from the preview
 * @param mask fields made visible with their values replaced by {@code maskString}
 * @param maskString replacement for masked field values
 * @param maxPreviewBytes maximum estimated UTF-8 size of accepted preview entries
 */
public record PreviewConfig(
        PreviewMode mode,
        List<PreviewField> include,
        List<PreviewField> exclude,
        List<PreviewField> mask,
        String maskString,
        int maxPreviewBytes) {
    public static final String DEFAULT_MASK_STRING = "***";
    public static final int DEFAULT_MAX_PREVIEW_BYTES = 4096;

    public PreviewConfig {
        Objects.requireNonNull(mode, "mode cannot be null");
        include = immutableFields(include, "include");
        exclude = immutableFields(exclude, "exclude");
        mask = immutableFields(mask, "mask");
        Objects.requireNonNull(maskString, "maskString cannot be null");
        if (maxPreviewBytes < 0) {
            throw new IllegalArgumentException("maxPreviewBytes cannot be negative");
        }
    }

    /** Creates a preview configuration builder. */
    public static Builder builder(PreviewMode mode) {
        return new Builder(mode);
    }

    private static List<PreviewField> immutableFields(List<PreviewField> fields, String name) {
        Objects.requireNonNull(fields, name + " cannot be null");
        if (fields.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(name + " cannot contain null");
        }
        return List.copyOf(fields);
    }

    /** Builder for {@link PreviewConfig}. */
    public static final class Builder {
        private final PreviewMode mode;
        private final List<PreviewField> include = new ArrayList<>();
        private final List<PreviewField> exclude = new ArrayList<>();
        private final List<PreviewField> mask = new ArrayList<>();
        private String maskString = DEFAULT_MASK_STRING;
        private int maxPreviewBytes = DEFAULT_MAX_PREVIEW_BYTES;

        private Builder(PreviewMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode cannot be null");
        }

        /** Adds fields that should be visible. */
        public Builder include(PreviewField... fields) {
            include.addAll(validFields(fields, "include"));
            return this;
        }

        /** Adds fields that should be hidden. */
        public Builder exclude(PreviewField... fields) {
            exclude.addAll(validFields(fields, "exclude"));
            return this;
        }

        /** Adds fields whose values should be masked. */
        public Builder mask(PreviewField... fields) {
            mask.addAll(validFields(fields, "mask"));
            return this;
        }

        /** Sets the value used for masked fields. */
        public Builder maskString(String maskString) {
            this.maskString = Objects.requireNonNull(maskString, "maskString cannot be null");
            return this;
        }

        /** Sets the maximum estimated UTF-8 preview size. */
        public Builder maxPreviewBytes(int maxPreviewBytes) {
            if (maxPreviewBytes < 0) {
                throw new IllegalArgumentException("maxPreviewBytes cannot be negative");
            }
            this.maxPreviewBytes = maxPreviewBytes;
            return this;
        }

        /** Returns the immutable preview configuration. */
        public PreviewConfig build() {
            return new PreviewConfig(mode, include, exclude, mask, maskString, maxPreviewBytes);
        }

        private static List<PreviewField> validFields(PreviewField[] fields, String name) {
            Objects.requireNonNull(fields, name + " cannot be null");
            if (Arrays.stream(fields).anyMatch(Objects::isNull)) {
                throw new NullPointerException(name + " cannot contain null");
            }
            return List.of(fields);
        }
    }
}
