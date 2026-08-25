// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.exception.SerDesException;

class SerDesPreviewTest {

    @Test
    void includeAllAppliesExcludeAndMaskRules() {
        var value = Map.of(
                "id",
                "123",
                "email",
                "alice@example.com",
                "ssn",
                "000-00-0000",
                "user",
                Map.of("name", "Alice", "role", "admin"));
        var config = PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                .exclude(PreviewField.anywhere("role"))
                .mask(PreviewField.anywhere("ssn"))
                .build();

        var preview = SerDesPreview.buildPreview(value, config);

        assertEquals("123", preview.get("id"));
        assertEquals("***", preview.get("ssn"));
        assertFalse(nested(preview, "user").containsKey("role"));
        assertEquals("Alice", nested(preview, "user").get("name"));
    }

    @Test
    void excludeAllIncludesSelectedAndMaskedFields() {
        var value = Map.of("id", "123", "email", "alice@example.com", "ssn", "000-00-0000");
        var config = PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
                .include(PreviewField.anywhere("id"))
                .mask(PreviewField.anywhere("ssn"))
                .build();

        var preview = SerDesPreview.buildPreview(value, config);

        assertEquals(Map.of("id", "123", "ssn", "***"), preview);
    }

    @Test
    void excludeWinsOverMask() {
        var config = PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                .exclude(PreviewField.anywhere("ssn"))
                .mask(PreviewField.anywhere("ssn"))
                .build();

        var preview = SerDesPreview.buildPreview(Map.of("id", "123", "ssn", "secret"), config);

        assertEquals(Map.of("id", "123"), preview);
    }

    @Test
    void pathAndAnywhereMatchingHaveDifferentScopes() {
        var value = Map.of("email", "root@example.com", "user", Map.of("email", "nested@example.com", "id", "user-1"));
        var pathConfig = PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
                .include(PreviewField.path("email"))
                .build();
        var anywhereConfig = PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
                .include(PreviewField.anywhere("email"))
                .build();

        var pathPreview = SerDesPreview.buildPreview(value, pathConfig);
        var anywherePreview = SerDesPreview.buildPreview(value, anywhereConfig);

        assertEquals(Map.of("email", "root@example.com"), pathPreview);
        assertEquals("root@example.com", anywherePreview.get("email"));
        assertEquals("nested@example.com", nested(anywherePreview, "user").get("email"));
    }

    @Test
    void arraysMergeFieldsAtTheirContainingPath() {
        var value = Map.of("items", List.of(Map.of("id", "first"), Map.of("email", "second@example.com")));
        var config = PreviewConfig.builder(PreviewMode.INCLUDE_ALL).build();

        var preview = SerDesPreview.buildPreview(value, config);

        assertEquals(Map.of("id", "first", "email", "second@example.com"), nested(preview, "items"));
    }

    @Test
    void customMaskStringAndByteBudgetAreApplied() {
        var value = new LinkedHashMap<String, Object>();
        value.put("first", "one");
        value.put("second", "two");
        value.put("secret", "hidden");
        var config = PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                .mask(PreviewField.anywhere("secret"))
                .maskString("[REDACTED]")
                .maxPreviewBytes(18)
                .build();

        var preview = SerDesPreview.buildPreview(value, config);

        assertEquals(1, preview.size());
        assertTrue(preview.containsKey("first"));
    }

    @Test
    void returnsNullWhenNoFieldsAreVisibleOrValueIsNotAnObject() {
        var config = PreviewConfig.builder(PreviewMode.EXCLUDE_ALL).build();

        assertNull(SerDesPreview.buildPreview(Map.of("id", "123"), config));
        assertNull(SerDesPreview.buildPreview("value", config));
        assertNull(SerDesPreview.buildPreview(List.of(Map.of("id", "123")), config));
    }

    @Test
    void jsonPreviewRejectsMalformedJsonAndSkipsDottedFieldNames() {
        var config = PreviewConfig.builder(PreviewMode.INCLUDE_ALL).build();

        assertThrows(SerDesException.class, () -> SerDesPreview.buildPreviewFromJson("not-json", config));
        assertEquals(
                Map.of("safe", "value"),
                SerDesPreview.buildPreviewFromJson("{\"safe\":\"value\",\"not.addressable\":\"secret\"}", config));
    }

    @Test
    void validatesConfiguration() {
        assertThrows(NullPointerException.class, () -> PreviewConfig.builder(null));
        assertNull(SerDesPreview.buildPreview(
                Map.of("id", "123"),
                PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                        .maxPreviewBytes(0)
                        .build()));
        assertThrows(IllegalArgumentException.class, () -> PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                .maxPreviewBytes(-1));
        assertThrows(IllegalArgumentException.class, () -> new PreviewField(" "));
        assertThrows(NullPointerException.class, () -> PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                .include((PreviewField) null));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> value, String field) {
        return (Map<String, Object>) value.get(field);
    }
}
