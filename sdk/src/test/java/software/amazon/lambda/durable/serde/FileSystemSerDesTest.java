// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.retry.RetryDecision;

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
        assertTrue(node.get("sha256").textValue().matches("[0-9a-f]{64}"));
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
    void verifiesInlineAndFilePayloadDigests() throws Exception {
        var context = new SerDesContext(realisticArn(), "1");
        var inlineSerDes = FileSystemSerDes.builder(tempDir)
                .storageMode(FileSystemSerDesMode.OVERFLOW)
                .build();
        var inline = (ObjectNode) MAPPER.readTree(runner.serialize(inlineSerDes, "value", context));
        inline.put("data", "\"tampered\"");

        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(inlineSerDes, inline.toString(), TypeToken.get(String.class), context));

        var fileSerDes = FileSystemSerDes.builder(tempDir).build();
        var fileEnvelope = runner.serialize(fileSerDes, "expected", context);
        var fileNode = MAPPER.readTree(fileEnvelope);
        var file = Path.of(fileNode.get("file").textValue());
        Files.writeString(file, "\"tampered\"");

        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(fileSerDes, fileEnvelope, TypeToken.get(String.class), context));
    }

    @Test
    void filePathContainsTheEnvelopeDigest() throws Exception {
        var envelope = MAPPER.readTree(runner.serialize(
                FileSystemSerDes.builder(tempDir).build(), "value", new SerDesContext(realisticArn(), "1")));

        assertTrue(
                envelope.get("file").textValue().contains(envelope.get("sha256").textValue()));
    }

    @Test
    void rejectsMissingAndMalformedPayloadDigest() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir)
                .storageMode(FileSystemSerDesMode.OVERFLOW)
                .build();
        var context = new SerDesContext(realisticArn(), "1");
        var envelope = (ObjectNode) MAPPER.readTree(runner.serialize(serDes, "value", context));

        envelope.remove("sha256");
        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(serDes, envelope.toString(), TypeToken.get(String.class), context));

        envelope.put("sha256", "not-a-digest");
        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(serDes, envelope.toString(), TypeToken.get(String.class), context));
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
    void retryablePreviewFailureCanBeRetried() throws Exception {
        var attempts = new AtomicInteger();
        var fileSystemSerDes = FileSystemSerDes.builder(tempDir)
                .previewGenerator(value -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new RetryableSerDesException("preview unavailable");
                    }
                    return Map.of("summary", "value");
                })
                .build();
        var serDes = new RetrySerDes(
                fileSystemSerDes, (failure, attempt) -> RetryDecision.retry(Duration.ZERO), delay -> {});

        var envelope = MAPPER.readTree(runner.serialize(serDes, "value", new SerDesContext(realisticArn(), "1")));

        assertEquals(2, attempts.get());
        assertEquals("value", envelope.get("preview").get("summary").textValue());
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
    void recognizesMalformedMarkerRegardlessOfWhitespaceOrFieldOrder() {
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var malformed = List.of(
                "{ \n  \"__durable_execution_filesystem_serdes\" : 1",
                "{\"precedingField\":true,\n  \"__durable_execution_filesystem_serdes\" : 1",
                "{\"\\u005f_durable_execution_filesystem_serdes\" : 1");

        for (var envelope : malformed) {
            assertThrows(SerDesException.class, () -> serDes.deserialize(envelope, TypeToken.get(String.class)));
        }
    }

    @Test
    void markerTextInsideStringDoesNotClaimMalformedJson() {
        var value = "{\"message\":\"__durable_execution_filesystem_serdes\"} trailing";
        var delegate = new SerDes() {
            @Override
            public String serialize(Object input) {
                return input.toString();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return (T) data;
            }
        };

        assertEquals(
                value,
                FileSystemSerDes.builder(tempDir)
                        .delegate(delegate)
                        .build()
                        .deserialize(value, TypeToken.get(String.class)));
    }

    @Test
    void rejectsUnsupportedAndOutOfRangeEnvelopeVersions() {
        var serDes = FileSystemSerDes.builder(tempDir).build();
        for (var version : List.of("2", "4294967297")) {
            var envelope = "{\"__durable_execution_filesystem_serdes\":"
                    + version
                    + ",\"data\":\"\\\"value\\\"\",\"sha256\":\""
                    + "0".repeat(64)
                    + "\"}";
            assertThrows(SerDesException.class, () -> serDes.deserialize(envelope, TypeToken.get(String.class)));
        }
    }

    @Test
    void rejectsMalformedUtf8FilePayload() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var context = new SerDesContext(realisticArn(), "1");
        var envelope = runner.serialize(serDes, "value", context);
        var file = Path.of(MAPPER.readTree(envelope).get("file").textValue());
        Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28});

        assertThrows(
                RetryableSerDesException.class,
                () -> runner.deserialize(serDes, envelope, TypeToken.get(String.class), context));
    }

    @Test
    void uriEncodingUsesReadableExecutionPathAndFlatUnsafeEntity() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var context = new SerDesContext(realisticArn(), "../unsafe/entity");

        var file = Path.of(MAPPER.readTree(runner.serialize(serDes, "value", context))
                .get("file")
                .textValue());

        assertEquals(tempDir.resolve("test").resolve("execution-name").resolve("invocation-id"), file.getParent());
        assertFalse(file.getFileName().toString().contains("/"));
        assertTrue(file.getFileName().toString().startsWith("..%2Funsafe%2Fentity-"));
    }

    @Test
    void malformedExecutionArnFallsBackToOneEncodedDirectory() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var arn = "local/test:execution";

        var file = Path.of(MAPPER.readTree(runner.serialize(serDes, "value", new SerDesContext(arn, "1")))
                .get("file")
                .textValue());

        assertEquals(1, tempDir.relativize(file.getParent()).getNameCount());
        assertEquals("local%2Ftest%3Aexecution", file.getParent().getFileName().toString());
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
    void failsClosedWhenProviderLacksSecureDirectoryStreams() throws Exception {
        var archive = tempDir.resolve("payloads.zip");
        try (var fileSystem =
                FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
            var serDes =
                    FileSystemSerDes.builder(fileSystem.getPath("/payloads")).build();

            var failure = assertThrows(
                    SerDesException.class,
                    () -> runner.serialize(serDes, "value", new SerDesContext(realisticArn(), "1")));

            assertTrue(failure.getMessage().contains("SecureDirectoryStream support"));
        }
    }

    @Test
    void rejectsSymbolicLinkDirectoryWhenWriting() throws Exception {
        var outside = Files.createTempDirectory(tempDir.getParent(), "outside-payloads-");
        Files.createSymbolicLink(tempDir.resolve("test"), outside);
        var serDes = FileSystemSerDes.builder(tempDir).build();

        assertThrows(
                SerDesException.class, () -> runner.serialize(serDes, "value", new SerDesContext(realisticArn(), "1")));
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void rejectsSymbolicLinkDirectoryWhenReading() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var context = new SerDesContext(realisticArn(), "1");
        var envelope = runner.serialize(serDes, "value", context);
        var executionDirectory = tempDir.resolve("test");
        var outside = Files.createTempDirectory(tempDir.getParent(), "outside-payloads-");
        var movedDirectory = outside.resolve("test");
        Files.move(executionDirectory, movedDirectory);
        Files.createSymbolicLink(executionDirectory, movedDirectory);

        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(serDes, envelope, TypeToken.get(String.class), context));
    }

    @Test
    void rejectsSymbolicLinkPayloadFile() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var context = new SerDesContext(realisticArn(), "1");
        var envelope = runner.serialize(serDes, "value", context);
        var file = Path.of(MAPPER.readTree(envelope).get("file").textValue());
        var outside = Files.createTempFile(tempDir.getParent(), "outside-payload-", ".json");
        Files.writeString(outside, "\"value\"");
        Files.delete(file);
        Files.createSymbolicLink(file, outside);

        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(serDes, envelope, TypeToken.get(String.class), context));
    }

    @Test
    void rejectsSymbolicLinkConfiguredBasePathAndAncestors() throws Exception {
        var outsideRoot = Files.createTempDirectory(tempDir.getParent(), "outside-root-");
        var linkedRoot = tempDir.resolve("linked-root");
        Files.createSymbolicLink(linkedRoot, outsideRoot);
        var rootSerDes = FileSystemSerDes.builder(linkedRoot).build();

        assertThrows(
                SerDesException.class,
                () -> runner.serialize(rootSerDes, "value", new SerDesContext(realisticArn(), "1")));

        var outsideAncestor = Files.createTempDirectory(tempDir.getParent(), "outside-ancestor-");
        var linkedAncestor = tempDir.resolve("linked-ancestor");
        Files.createSymbolicLink(linkedAncestor, outsideAncestor);
        var nestedSerDes =
                FileSystemSerDes.builder(linkedAncestor.resolve("payloads")).build();

        assertThrows(
                SerDesException.class,
                () -> runner.serialize(nestedSerDes, "value", new SerDesContext(realisticArn(), "1")));
        assertFalse(Files.exists(outsideAncestor.resolve("payloads")));
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
