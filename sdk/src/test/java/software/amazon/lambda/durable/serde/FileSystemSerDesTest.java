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
import software.amazon.lambda.durable.exception.RetryableSerDesException;
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
    void checkpointEnvelopeLimitCanBeIncreasedForLargerInlinePayloads() throws Exception {
        var value = "x".repeat(300_000);
        var context = new SerDesContext(realisticArn(), "1");
        var defaultSerDes = FileSystemSerDes.builder(tempDir)
                .storageMode(FileSystemSerDesMode.OVERFLOW)
                .build();
        var largerEnvelopeSerDes = FileSystemSerDes.builder(tempDir)
                .storageMode(FileSystemSerDesMode.OVERFLOW)
                .checkpointEnvelopeLimitBytes(512 * 1024)
                .build();

        assertTrue(
                MAPPER.readTree(runner.serialize(defaultSerDes, value, context)).hasNonNull("file"));
        assertTrue(MAPPER.readTree(runner.serialize(largerEnvelopeSerDes, value, context))
                .hasNonNull("data"));
    }

    @Test
    void checkpointEnvelopeLimitMustBePositive() {
        var zeroFailure = assertThrows(IllegalArgumentException.class, () -> FileSystemSerDes.builder(tempDir)
                .checkpointEnvelopeLimitBytes(0));
        var negativeFailure = assertThrows(IllegalArgumentException.class, () -> FileSystemSerDes.builder(tempDir)
                .checkpointEnvelopeLimitBytes(-1));

        assertEquals("checkpointEnvelopeLimitBytes must be positive", zeroFailure.getMessage());
        assertEquals("checkpointEnvelopeLimitBytes must be positive", negativeFailure.getMessage());
    }

    @Test
    void checkpointEnvelopeLimitAlsoAppliesToFileEnvelopes() {
        var serDes = FileSystemSerDes.builder(tempDir)
                .checkpointEnvelopeLimitBytes(1)
                .build();

        var failure = assertThrows(
                SerDesException.class, () -> runner.serialize(serDes, "value", new SerDesContext(realisticArn(), "1")));

        assertTrue(failure.getMessage().contains("checkpoint payload limit"));
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
        assertTrue(file.getFileName().toString().matches("[0-9a-f]{64}-[0-9a-f]{64}-[0-9a-f-]{36}\\.json"));
        assertEquals("preview", node.get("preview").get("summary").textValue());
    }

    @Test
    void repeatedPayloadsUseDistinctImmutableFiles() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var context = new SerDesContext(realisticArn(), "1");

        var first = Path.of(MAPPER.readTree(runner.serialize(serDes, "value", context))
                .get("file")
                .textValue());
        var second = Path.of(MAPPER.readTree(runner.serialize(serDes, "value", context))
                .get("file")
                .textValue());

        assertFalse(first.equals(second));
        assertEquals("\"value\"", Files.readString(first));
        assertEquals("\"value\"", Files.readString(second));
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
    void structuredPreviewSelectsAndMasksFields() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir)
                .previewConfig(PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
                        .include(PreviewField.anywhere("id"), PreviewField.path("customer.status"))
                        .mask(PreviewField.anywhere("email"))
                        .build())
                .build();
        var value = Map.of(
                "id",
                "order-1",
                "email",
                "root@example.com",
                "customer",
                Map.of("status", "ready", "email", "customer@example.com", "secret", "hidden"));

        var preview = MAPPER.readTree(runner.serialize(serDes, value, new SerDesContext(realisticArn(), "1")))
                .get("preview");

        assertEquals("order-1", preview.get("id").textValue());
        assertEquals("***", preview.get("email").textValue());
        assertEquals("ready", preview.get("customer").get("status").textValue());
        assertEquals("***", preview.get("customer").get("email").textValue());
        assertFalse(preview.get("customer").has("secret"));
    }

    @Test
    void unmarkedDataAndFileObjectsAreDelegatedNormally() {
        var serDes = FileSystemSerDes.builder(tempDir).build();

        assertEquals(
                Map.of("data", "value"),
                serDes.deserialize("{\"data\":\"value\"}", new TypeToken<Map<String, String>>() {}));
        assertEquals(
                Map.of("file", "value"),
                serDes.deserialize("{\"file\":\"value\"}", new TypeToken<Map<String, String>>() {}));
    }

    @Test
    void rejectsFileOutsideConfiguredBasePath() throws Exception {
        var externalFile = Files.createTempFile("filesystem-serdes", ".json");
        Files.writeString(externalFile, "\"secret\"", StandardCharsets.UTF_8);
        var envelope = MAPPER.writeValueAsString(Map.of(
                "__durable_execution_filesystem_serdes", 1, "file", externalFile.toString(), "sha256", "0".repeat(64)));
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

    @Test
    void missingPayloadFileIsRetryable() throws Exception {
        var missing = tempDir.resolve("missing.json");
        var envelope = MAPPER.writeValueAsString(Map.of(
                "__durable_execution_filesystem_serdes", 1, "file", missing.toString(), "sha256", "0".repeat(64)));
        var serDes = FileSystemSerDes.builder(tempDir).build();

        assertThrows(RetryableSerDesException.class, () -> serDes.deserialize(envelope, TypeToken.get(String.class)));
    }

    @Test
    void rejectsTrailingAndDuplicateFieldsInMarkedEnvelope() {
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var validDataEnvelope = "{\"__durable_execution_filesystem_serdes\":1,\"data\":\"\\\"value\\\"\"}";

        assertThrows(
                SerDesException.class,
                () -> serDes.deserialize(validDataEnvelope + " true", TypeToken.get(String.class)));
        assertThrows(
                SerDesException.class,
                () -> serDes.deserialize(
                        "{\"__durable_execution_filesystem_serdes\":1,"
                                + "\"__durable_execution_filesystem_serdes\":2,\"data\":\"\\\"value\\\"\"}",
                        TypeToken.get(String.class)));
    }

    @Test
    void customDelegateControlsValueEncoding() throws Exception {
        var jackson = new JacksonSerDes();
        var delegate = new SerDes() {
            @Override
            public String serialize(Object value) {
                return "custom:" + jackson.serialize(value);
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return jackson.deserialize(data.substring("custom:".length()), typeToken);
            }
        };
        var serDes = FileSystemSerDes.builder(tempDir).delegate(delegate).build();
        var context = new SerDesContext(realisticArn(), "1");

        var envelope = runner.serialize(serDes, new Value("custom"), context);
        var file = Path.of(MAPPER.readTree(envelope).get("file").textValue());

        assertTrue(Files.readString(file).startsWith("custom:"));
        assertEquals(new Value("custom"), runner.deserialize(serDes, envelope, TypeToken.get(Value.class), context));
    }

    private static String realisticArn() {
        return "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST"
                + "/durable-execution/execution-name/invocation-id";
    }

    record Value(String value) {}
}
