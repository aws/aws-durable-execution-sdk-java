// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;

class FileSystemSerDesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private ExecutorService executor;
    private SerDesRunner runner;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        runner = new SerDesRunner(executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void serializesNormallyWhenNoDurableContextExists() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir).build();

        assertEquals("\"input\"", serDes.serialize("input"));
        assertEquals("input", serDes.deserialize("\"input\"", TypeToken.get(String.class)));
        try (var files = Files.walk(tempDir)) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void alwaysModeStoresAndLoadsPayload() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var context = new SerDesContext(realisticArn(), "operation/1/result");

        var envelope = runner.serialize(serDes, new Value("stored"), context);
        var node = MAPPER.readTree(envelope);

        assertEquals(1, node.get("__durable_execution_filesystem_serdes").intValue());
        assertTrue(node.hasNonNull("file"));
        assertTrue(node.hasNonNull("sha256"));
        assertEquals(new Value("stored"), runner.deserialize(serDes, envelope, TypeToken.get(Value.class), context));
        assertTrue(Files.exists(Path.of(node.get("file").textValue())));
    }

    @Test
    void overflowModeKeepsSmallPayloadInline() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir)
                .storageMode(FileSystemSerDesMode.OVERFLOW)
                .build();
        var context = new SerDesContext(realisticArn(), "1");

        var envelope = runner.serialize(serDes, "small", context);
        var node = MAPPER.readTree(envelope);

        assertTrue(node.hasNonNull("data"));
        assertFalse(node.has("file"));
        assertEquals("small", runner.deserialize(serDes, envelope, TypeToken.get(String.class), context));
    }

    @Test
    void overflowModeStoresLargePayload() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir)
                .storageMode(FileSystemSerDesMode.OVERFLOW)
                .build();
        var context = new SerDesContext(realisticArn(), "1");

        var envelope = runner.serialize(serDes, "x".repeat(300_000), context);

        assertTrue(MAPPER.readTree(envelope).hasNonNull("file"));
    }

    @Test
    void supportsHashPathEncodingAndPreview() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir)
                .pathEncoding(FileSystemPathEncoding.HASH)
                .previewGenerator(value -> Map.of("summary", ((Value) value).value()))
                .build();
        var context = new SerDesContext(realisticArn(), "../unsafe/entity");

        var node = MAPPER.readTree(runner.serialize(serDes, new Value("preview"), context));
        var file = Path.of(node.get("file").textValue());

        assertEquals(64, tempDir.relativize(file).getName(0).toString().length());
        assertTrue(file.getFileName().toString().matches("[0-9a-f]{64}-[0-9a-f]{64}\\.json"));
        assertEquals("preview", node.get("preview").get("summary").textValue());
    }

    @Test
    void rejectsPreviewThatMakesFileEnvelopeTooLarge() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir)
                .previewGenerator(value -> Map.of("large", "x".repeat(300_000)))
                .build();
        var context = new SerDesContext(realisticArn(), "1");

        assertThrows(SerDesException.class, () -> runner.serialize(serDes, "value", context));
        try (var files = Files.walk(tempDir)) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void readsJavaScriptFilesystemEnvelope() throws Exception {
        var payloadFile = tempDir.resolve("js-payload.json");
        Files.writeString(payloadFile, "{\"value\":\"js\"}", StandardCharsets.UTF_8);
        var envelope = MAPPER.writeValueAsString(Map.of("file", payloadFile.toString()));
        var serDes = FileSystemSerDes.builder(tempDir).build();

        assertEquals(new Value("js"), serDes.deserialize(envelope, TypeToken.get(Value.class)));
    }

    @Test
    void rejectsFileOutsideConfiguredBasePath() throws Exception {
        var externalFile = Files.createTempFile("filesystem-serdes", ".json");
        Files.writeString(externalFile, "\"secret\"", StandardCharsets.UTF_8);
        var envelope = MAPPER.writeValueAsString(Map.of("file", externalFile.toString()));
        var serDes = FileSystemSerDes.builder(tempDir).build();

        assertThrows(SerDesException.class, () -> serDes.deserialize(envelope, TypeToken.get(String.class)));
    }

    @Test
    void rejectsMalformedRecognizedEnvelope() {
        var serDes = FileSystemSerDes.builder(tempDir).build();

        assertThrows(
                SerDesException.class,
                () -> serDes.deserialize(
                        "{\"__durable_execution_filesystem_serdes\":1,\"data\":\"x\",\"file\":\"y\"}",
                        TypeToken.get(String.class)));
    }

    private static String realisticArn() {
        return "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST"
                + "/durable-execution/execution-name/invocation-id";
    }

    record Value(String value) {}
}
