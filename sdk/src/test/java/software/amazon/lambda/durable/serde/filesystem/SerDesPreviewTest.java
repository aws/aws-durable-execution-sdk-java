// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
    void includeAllPreservesScalarArrayFields() {
        var preview = SerDesPreview.buildPreviewFromJson(
                "{\"tags\":[\"a\",\"b\"]}",
                PreviewConfig.builder(PreviewMode.INCLUDE_ALL).build());

        assertEquals(Map.of("tags", List.of("a", "b")), preview);
    }

    @Test
    void excludeAllPreservesExplicitlyIncludedScalarArrayFields() {
        var preview = SerDesPreview.buildPreview(
                Map.of("tags", List.of("a", "b"), "hidden", List.of("c")),
                PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
                        .include(PreviewField.path("tags"))
                        .build());

        assertEquals(Map.of("tags", List.of("a", "b")), preview);
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
    void nestedPreviewUsesTheExactSerializedByteBudget() {
        var value = Map.of("a", Map.of("b", "x"));

        var tooSmall = SerDesPreview.buildPreview(
                value,
                PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                        .maxPreviewBytes(14)
                        .build());
        var exactFit = SerDesPreview.buildPreview(
                value,
                PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                        .maxPreviewBytes(15)
                        .build());

        assertNull(tooSmall);
        assertEquals(value, exactFit);
    }

    @Test
    void streamingJsonPreviewStopsBeforeMaterializingAnOversizedField() {
        var value = "{\"first\":\"one\",\"oversized\":\"" + "x".repeat(4 * 1024 * 1024) + "\",\"later\":\"three\"}";

        var preview = SerDesPreview.buildPreviewFromJson(
                value,
                PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                        .maxPreviewBytes(15)
                        .build());

        assertEquals(Map.of("first", "one"), preview);
    }

    @Test
    void streamingObjectPreviewStopsBeforeMaterializingAnOversizedField() {
        var value = new LinkedHashMap<String, Object>();
        value.put("first", "one");
        value.put("oversized", "x".repeat(4 * 1024 * 1024));
        value.put("later", "three");

        var preview = SerDesPreview.buildPreview(
                value,
                PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                        .maxPreviewBytes(15)
                        .build());

        assertEquals(Map.of("first", "one"), preview);
    }

    @Test
    void streamingPreviewStopsAtOversizedNamesAndNumbers() {
        var config = PreviewConfig.builder(PreviewMode.INCLUDE_ALL)
                .maxPreviewBytes(15)
                .build();

        var oversizedName = SerDesPreview.buildPreviewFromJson(
                "{\"first\":\"one\",\"" + "n".repeat(1024 * 1024) + "\":\"value\"}", config);
        var oversizedNumber = SerDesPreview.buildPreviewFromJson(
                "{\"first\":\"one\",\"number\":" + "9".repeat(1024 * 1024) + "}", config);

        assertEquals(Map.of("first", "one"), oversizedName);
        assertEquals(Map.of("first", "one"), oversizedNumber);
    }

    @Test
    void oversizedStringTokenRemainsBoundedUnderConstrainedHeap() throws Exception {
        runHeapProbe("json");
        runHeapProbe("object");
    }

    @Test
    void returnsNullWhenNoFieldsAreVisibleOrValueIsNotAnObject() {
        var config = PreviewConfig.builder(PreviewMode.EXCLUDE_ALL).build();

        assertNull(SerDesPreview.buildPreview(Map.of("id", "123"), config));
        assertNull(SerDesPreview.buildPreview("value", config));
        assertNull(SerDesPreview.buildPreview(List.of(Map.of("id", "123")), config));
    }

    @Test
    void objectPreviewUsesJacksonSerDesTimeFormats() {
        var instant = Instant.parse("2026-08-26T03:30:00Z");
        var duration = Duration.ofMinutes(5);
        var localDateTime = LocalDateTime.parse("2026-08-26T03:30:00");
        var config = PreviewConfig.builder(PreviewMode.INCLUDE_ALL).build();

        var preview = SerDesPreview.buildPreview(new TemporalPayload(instant, duration, localDateTime), config);

        assertEquals("2026-08-26T03:30:00Z", preview.get("instant"));
        assertEquals(0, new BigDecimal("300").compareTo((BigDecimal) preview.get("duration")));
        assertEquals("2026-08-26T03:30:00", preview.get("localDateTime"));
    }

    @Test
    void jsonPreviewRejectsMalformedJsonAndSkipsDottedFieldNames() {
        var config = PreviewConfig.builder(PreviewMode.INCLUDE_ALL).build();

        assertThrows(SerDesException.class, () -> SerDesPreview.buildPreviewFromJson("not-json", config));
        assertThrows(
                SerDesException.class,
                () -> SerDesPreview.buildPreviewFromJson("{\"id\":\"1\"}{\"secret\":\"value\"}", config));
        assertThrows(SerDesException.class, () -> SerDesPreview.buildPreviewFromJson("[] {}", config));
        assertThrows(SerDesException.class, () -> SerDesPreview.buildPreviewFromJson("\"value\" {}", config));
        assertNull(SerDesPreview.buildPreviewFromJson("[]", config));
        assertNull(SerDesPreview.buildPreviewFromJson("\"value\"", config));
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

    private static void runHeapProbe(String mode) throws IOException, InterruptedException {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var process = new ProcessBuilder(
                        java, "-Xmx32m", "-cp", classpath, SerDesPreviewHeapProbe.class.getName(), mode)
                .redirectErrorStream(true)
                .start();
        var finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(finished, output);
        assertEquals(0, process.exitValue(), output);
    }

    private record TemporalPayload(Instant instant, Duration duration, LocalDateTime localDateTime) {}
}
