// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;

class FileSystemSerDesTest {
    private static final SerDesContext CONTEXT = new SerDesContext(
            "arn:aws:lambda:us-east-1:123456789012:function:test:1/durable-execution/order/invocation",
            "operation/1/result");
    private static final TypeToken<String> STRING = TypeToken.get(String.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(FileSystemPathEncoding.class)
    void roundTripsGenericValuesAndKeepsEarlierReferencesReadable(FileSystemPathEncoding encoding) throws Exception {
        var serDes = FileSystemSerDes.builder(directory).pathEncoding(encoding).build();
        var first = serDes.serialize(List.of("first", "日本語"), CONTEXT);
        var second = serDes.serialize(List.of("second"), CONTEXT);
        var type = new TypeToken<List<String>>() {};
        assertEquals(List.of("first", "日本語"), serDes.deserialize(first, type, CONTEXT));
        assertEquals(List.of("second"), serDes.deserialize(second, type, CONTEXT));
        assertNotEquals(file(first), file(second));
        assertEquals("[\"first\",\"日本語\"]", Files.readString(file(first)));
        assertTrue(file(first).isAbsolute());
    }

    @Test
    void overflowMeasuresEscapedUtf8EnvelopeRatherThanCharacterCount() throws Exception {
        var serDes = FileSystemSerDes.builder(directory)
                .storageMode(FileSystemStorageMode.OVERFLOW)
                .build();
        for (var value : List.of("small", "x".repeat(260000), "é".repeat(140000), "\"".repeat(100000))) {
            var envelope = serDes.serialize(value, CONTEXT);
            boolean shouldOffload =
                    JSON.writeValueAsBytes(Map.of("data", JSON.writeValueAsString(value))).length > 255 * 1024;
            assertEquals(shouldOffload, JSON.readTree(envelope).has("file"));
            assertEquals(value, serDes.deserialize(envelope, STRING, CONTEXT));
        }
    }

    @Test
    void overflowBoundaryAndSmallPayloadsDoNotTouchFilesystem() throws Exception {
        var base = directory.resolve("unused");
        var serDes = FileSystemSerDes.builder(base)
                .storageMode(FileSystemStorageMode.OVERFLOW)
                .build();
        int overhead = JSON.writeValueAsBytes(Map.of("data", "\"\"")).length;
        var value = "x".repeat(255 * 1024 - overhead);
        assertTrue(JSON.readTree(serDes.serialize(value, CONTEXT)).has("data"));
        assertFalse(Files.exists(base));
        assertTrue(JSON.readTree(serDes.serialize(value + "x", CONTEXT)).has("file"));
    }

    @Test
    void preservesNullWithoutCreatingFiles() {
        var base = directory.resolve("unused");
        var serDes = FileSystemSerDes.builder(base).build();
        assertNull(serDes.serialize(null, CONTEXT));
        assertNull(serDes.deserialize(null, STRING, CONTEXT));
        assertFalse(Files.exists(base));
    }

    @Test
    void wrapsCustomEncodingAndGeneratesPreviewOnlyForFiles() throws Exception {
        var delegate = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                return "prefix:" + super.serialize(value);
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> type) {
                return super.deserialize(data.substring("prefix:".length()), type);
            }
        };
        var serDes = FileSystemSerDes.builder(directory)
                .delegate(delegate)
                .previewGenerator(value -> Map.of("summary", "redacted"))
                .build();
        var envelope = serDes.serialize("secret", CONTEXT);
        assertEquals(
                "redacted",
                JSON.readTree(envelope).path("preview").path("summary").asText());
        assertEquals("prefix:\"secret\"", Files.readString(file(envelope)));
        assertEquals("secret", serDes.deserialize(envelope, STRING, CONTEXT));
        var inline = FileSystemSerDes.builder(directory)
                .delegate(delegate)
                .storageMode(FileSystemStorageMode.OVERFLOW)
                .previewGenerator(value -> {
                    throw new AssertionError("Inline values do not need previews");
                })
                .build();
        assertEquals("small", inline.deserialize(inline.serialize("small", CONTEXT), STRING, CONTEXT));
    }

    @Test
    void rejectsOversizedPreviewAndRemovesUnpublishedFile() throws Exception {
        var serDes = FileSystemSerDes.builder(directory)
                .previewGenerator(value -> Map.of("large", "x".repeat(256 * 1024)))
                .build();
        assertThrows(SerDesException.class, () -> serDes.serialize("value", CONTEXT));
        try (var files = Files.walk(directory)) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "null",
                "[]",
                "{}",
                "not-json",
                "{\"data\":null}",
                "{\"file\":7}",
                "{\"file\":\"\"}",
                "{\"file\":\"a\",\"data\":\"b\"}"
            })
    void rejectsMalformedEnvelopes(String envelope) {
        var serDes = FileSystemSerDes.builder(directory).build();
        assertThrows(SerDesException.class, () -> serDes.deserialize(envelope, STRING, CONTEXT));
    }

    @Test
    void rejectsAmbiguousJsonAndPreservesWriteFailureCauses() throws Exception {
        var serDes = FileSystemSerDes.builder(directory).build();
        for (var envelope : List.of("{\"data\":\"1\"} {}", "{\"data\":\"1\",\"data\":\"2\"}")) {
            assertThrows(SerDesException.class, () -> serDes.deserialize(envelope, STRING, CONTEXT));
        }
        var blocked = FileSystemSerDes.builder(Files.writeString(directory.resolve("not-a-directory"), "file"))
                .build();
        var failure = assertThrows(SerDesException.class, () -> blocked.serialize("value", CONTEXT));
        assertInstanceOf(IOException.class, failure.getCause());
    }

    @Test
    void requiresExplicitIdentityEvenForNullAndInlineValues() {
        var serDes = FileSystemSerDes.builder(directory)
                .storageMode(FileSystemStorageMode.OVERFLOW)
                .build();
        assertThrows(SerDesException.class, () -> serDes.serialize("value"));
        assertThrows(SerDesException.class, () -> serDes.deserialize("{}", STRING));
        assertThrows(SerDesException.class, () -> serDes.serialize(null, null));
        assertThrows(SerDesException.class, () -> serDes.serialize("value", new SerDesContext("", "entity")));
        assertThrows(SerDesException.class, () -> serDes.serialize("value", new SerDesContext("arn", " ")));
    }

    @Test
    void rejectsPathsOutsideBaseAndMissingFiles() throws Exception {
        var base = directory.resolve("payloads");
        var serDes = FileSystemSerDes.builder(base).build();
        var outside = Files.writeString(directory.resolve("outside.json"), "\"outside\"");
        for (var path : List.of(outside, base.resolve("../outside.json"), Path.of("relative.json"))) {
            var envelope = JSON.writeValueAsString(Map.of("file", path.toString()));
            assertThrows(SerDesException.class, () -> serDes.deserialize(envelope, STRING, CONTEXT));
        }
        var envelope = serDes.serialize("value", CONTEXT);
        Files.delete(file(envelope));
        var error = assertThrows(SerDesException.class, () -> serDes.deserialize(envelope, STRING, CONTEXT));
        assertInstanceOf(IOException.class, error.getCause());
    }

    @Test
    void rejectsSymlinkFilesAndExecutionDirectories() throws Exception {
        var serDes = FileSystemSerDes.builder(directory).build();
        var envelope = serDes.serialize("value", CONTEXT);
        var path = file(envelope);
        Files.delete(path);
        Files.createSymbolicLink(path, Files.writeString(directory.resolve("other.json"), "\"other\""));
        assertThrows(SerDesException.class, () -> serDes.deserialize(envelope, STRING, CONTEXT));

        var base = directory.resolve("base");
        Files.createDirectory(base);
        Files.createSymbolicLink(base.resolve("execution"), directory);
        var other = FileSystemSerDes.builder(base).build();
        assertThrows(SerDesException.class, () -> other.serialize("value", new SerDesContext("execution", "entity")));
    }

    @Test
    void encodesTraversalAndSeparatesExecutionsAndRoles() throws Exception {
        var serDes = FileSystemSerDes.builder(directory).build();
        var paths = new ArrayList<Path>();
        for (var context : List.of(
                new SerDesContext("..", "../../result"),
                new SerDesContext(".", "../../result"),
                CONTEXT,
                new SerDesContext(CONTEXT.durableExecutionArn(), "operation/1/exception"),
                new SerDesContext(
                        CONTEXT.durableExecutionArn().replace("123456789012", "999999999999"), CONTEXT.entityId()))) {
            paths.add(file(serDes.serialize("value", context)));
        }
        assertEquals(paths.size(), paths.stream().distinct().count());
        assertTrue(paths.stream().allMatch(path -> path.normalize().startsWith(directory)));
    }

    @Test
    void hashSupportsLongIdentitiesAndConcurrentWrites() throws Exception {
        var serDes = FileSystemSerDes.builder(directory)
                .pathEncoding(FileSystemPathEncoding.HASH)
                .build();
        var context = new SerDesContext("arn/".repeat(1000), "entity/".repeat(1000));
        var executor = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < 16; i++) {
                var value = "value-" + i;
                tasks.add(() -> serDes.serialize(value, context));
            }
            var results = executor.invokeAll(tasks);
            for (int i = 0; i < results.size(); i++) {
                var envelope = results.get(i).get();
                assertEquals("value-" + i, serDes.deserialize(envelope, STRING, context));
                assertEquals(
                        64, file(envelope).getParent().getFileName().toString().length());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static Path file(String envelope) throws Exception {
        return Path.of(JSON.readTree(envelope).get("file").asText());
    }
}
