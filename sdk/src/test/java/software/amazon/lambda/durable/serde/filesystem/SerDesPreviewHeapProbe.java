// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.filesystem;

import java.util.LinkedHashMap;
import java.util.Map;

/** Runs oversized-token preview generation in a deliberately constrained JVM. */
public final class SerDesPreviewHeapProbe {
    private static final int OVERSIZED_FIELD_LENGTH = 8 * 1024 * 1024;
    private static final int OVERSIZED_MASK_LENGTH = 16 * 1024 * 1024;

    private SerDesPreviewHeapProbe() {}

    public static void main(String[] args) {
        Map<String, Object> preview;
        if ("json".equals(args[0])) {
            var config = PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                    .maxPreviewBytes(15)
                    .build();
            preview = SerDesPreview.buildPreviewFromJson(
                    "{\"first\":\"one\",\"oversized\":\"" + "x".repeat(OVERSIZED_FIELD_LENGTH) + "\"}", config);
        } else if ("object".equals(args[0])) {
            var config = PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                    .maxPreviewBytes(15)
                    .build();
            var value = new LinkedHashMap<String, Object>();
            value.put("first", "one");
            value.put("oversized", "x".repeat(OVERSIZED_FIELD_LENGTH));
            preview = SerDesPreview.buildPreview(value, config);
        } else if ("mask".equals(args[0])) {
            var config = PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                    .mask(PreviewField.anywhere("secret"))
                    .maskString("x".repeat(OVERSIZED_MASK_LENGTH))
                    .maxPreviewBytes(15)
                    .build();
            preview = SerDesPreview.buildPreview(Map.of("secret", "hidden"), config);
            if (preview != null) {
                throw new AssertionError("Unexpected masked preview: " + preview);
            }
            return;
        } else {
            throw new IllegalArgumentException("Unknown probe mode: " + args[0]);
        }

        if (!Map.of("first", "one").equals(preview)) {
            throw new AssertionError("Unexpected preview: " + preview);
        }
    }
}
