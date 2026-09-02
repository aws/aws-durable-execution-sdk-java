// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PayloadPreviewTest {
    @Test
    void includeAllExcludesAndMasksSelectedFields() {
        var config = PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                .exclude(PreviewField.anywhere("internal"))
                .mask(PreviewField.anywhere("email"))
                .build();

        var preview = PayloadPreview.buildPreview(
                Map.of(
                        "id", "123",
                        "email", "customer@example.com",
                        "nested", Map.of("internal", "secret", "status", "ready")),
                config);

        assertEquals("123", preview.get("id"));
        assertEquals("***", preview.get("email"));
        var nested = nested(preview, "nested");
        assertFalse(nested.containsKey("internal"));
        assertEquals("ready", nested.get("status"));
    }

    @Test
    void excludeAllIncludesExactPathAndScalarArray() {
        var config = PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
                .include(PreviewField.path("customer.status"), PreviewField.anywhere("tags"))
                .build();

        var preview = PayloadPreview.buildPreview(
                Map.of(
                        "customer", Map.of("status", "ready", "name", "Ada"),
                        "tags", List.of("a", "b"),
                        "ignored", true),
                config);

        assertEquals("ready", nested(preview, "customer").get("status"));
        assertEquals(List.of("a", "b"), preview.get("tags"));
        assertFalse(preview.containsKey("ignored"));
    }

    @Test
    void excludeAllExactContainerPathIncludesSubtreeWithNestedPolicies() {
        var config = PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
                .include(PreviewField.path("customer"))
                .exclude(PreviewField.path("customer.internal"))
                .mask(PreviewField.path("customer.email"))
                .build();

        var preview = PayloadPreview.buildPreview(
                Map.of(
                        "customer",
                        Map.of(
                                "name", "Ada",
                                "email", "customer@example.com",
                                "internal", "secret",
                                "address", Map.of("city", "Seattle")),
                        "ignored",
                        true),
                config);

        var customer = nested(preview, "customer");
        assertEquals("Ada", customer.get("name"));
        assertEquals("***", customer.get("email"));
        assertFalse(customer.containsKey("internal"));
        assertEquals("Seattle", nested(customer, "address").get("city"));
        assertFalse(preview.containsKey("ignored"));
    }

    @Test
    void literalDotsArePreservedAndEscapedPathsRemainUnambiguous() {
        var value = new LinkedHashMap<String, Object>();
        value.put("customer.email", "literal@example.com");
        value.put("customer", Map.of("email", "nested@example.com"));
        value.put("profile", Map.of("contact.email", "private@example.com"));
        var config = PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
                .include(PreviewField.path("customer\\.email"))
                .mask(PreviewField.path("profile.contact\\.email"))
                .build();

        var preview = PayloadPreview.buildPreview(value, config);

        assertEquals("literal@example.com", preview.get("customer.email"));
        assertFalse(preview.containsKey("customer"));
        assertEquals("***", nested(preview, "profile").get("contact.email"));
    }

    @Test
    void includeAllPreservesLiteralDotAndBackslashKeys() {
        var preview = PayloadPreview.buildPreview(
                Map.of("customer.email", "literal", "path\\name", "backslash"),
                PreviewConfig.builder(PreviewMode.INCLUDE_ALL).build());

        assertEquals("literal", preview.get("customer.email"));
        assertEquals("backslash", preview.get("path\\name"));
    }

    @Test
    void temporalValuesUseJacksonSerDesCompatibleEncoding() {
        var preview = PayloadPreview.buildPreview(
                Map.of("createdAt", Instant.parse("2026-09-01T00:00:00Z")),
                PreviewConfig.builder(PreviewMode.INCLUDE_ALL).build());

        assertEquals("2026-09-01T00:00:00Z", preview.get("createdAt"));
    }

    @Test
    void containerArraysAreOmittedInsteadOfCollapsingPositions() {
        var value = Map.of(
                "items",
                List.of(Map.of("id", "first", "secret", "one"), Map.of("id", "second", "secret", "two")),
                "status",
                "ready");
        var config = PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                .include(PreviewField.anywhere("id"))
                .mask(PreviewField.anywhere("secret"))
                .build();

        var preview = PayloadPreview.buildPreview(value, config);

        assertFalse(preview.containsKey("items"));
        assertEquals("ready", preview.get("status"));
    }

    @Test
    void previewBudgetUsesFinalNestedJsonSize() {
        var config = PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                .maxPreviewBytes(20)
                .build();
        var value = new LinkedHashMap<String, Object>();
        value.put("a", Map.of("b", "value"));
        value.put("c", "later");

        var preview = PayloadPreview.buildPreview(value, config);

        assertTrue(preview.containsKey("a"));
        assertFalse(preview.containsKey("c"));
    }

    @Test
    void nonObjectPayloadHasNoStructuredPreview() {
        assertNull(PayloadPreview.buildPreview(
                List.of("a", "b"),
                PreviewConfig.builder(PreviewMode.INCLUDE_ALL).build()));
    }

    @Test
    void nonJsonSerializedPayloadHasNoStructuredPreview() {
        assertNull(PayloadPreview.buildPreviewFromJson(
                "raw-error-data", PreviewConfig.builder(PreviewMode.INCLUDE_ALL).build()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> preview, String field) {
        return (Map<String, Object>) preview.get(field);
    }
}
