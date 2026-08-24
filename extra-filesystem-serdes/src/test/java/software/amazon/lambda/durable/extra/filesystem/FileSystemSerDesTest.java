// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extra.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.serde.SerDesPayloadKind;
import software.amazon.lambda.durable.serde.SerDesRunner;

class FileSystemSerDesTest {
    private static final String ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:orders:1/durable-execution/execution-1/invocation-1";
    private static final String ENVELOPE_MARKER = "__durable_execution_filesystem_serdes";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path basePath;

    @Test
    void standaloneModeWritesDelegatePayloadAndReplaysIt() throws Exception {
        var serDes = FileSystemSerDes.builder(basePath).build();
        var runner = new SerDesRunner(null);

        var envelope = runner.serialize(serDes, Map.of("id", 42), context());
        var json = MAPPER.readTree(envelope);
        var file = Path.of(json.get("file").textValue());

        assertEquals(1, json.get(ENVELOPE_MARKER).intValue());
        assertTrue(file.startsWith(basePath.resolve("orders/execution-1/invocation-1")));
        assertEquals("{\"id\":42}", Files.readString(file));
        assertEquals(
                Map.of("id", 42),
                runner.deserialize(serDes, envelope, new TypeToken<Map<String, Integer>>() {}, context()));
    }

    @Test
    void stageModeComposesWithValueCodec() throws Exception {
        var stage = FileSystemSerDes.stageBuilder(basePath).build();
        var pipeline = new JacksonSerDes().then(stage);
        var runner = new SerDesRunner(null);

        var envelope = runner.serialize(pipeline, Map.of("id", 42), context());
        var file = Path.of(MAPPER.readTree(envelope).get("file").textValue());

        assertEquals("{\"id\":42}", Files.readString(file));
        assertEquals(
                Map.of("id", 42),
                runner.deserialize(pipeline, envelope, new TypeToken<Map<String, Integer>>() {}, context()));
        assertThrows(SerDesException.class, () -> runner.serialize(stage, Map.of("id", 42), context()));
        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(stage, envelope, TypeToken.get(Integer.class), context()));
        assertThrows(IllegalStateException.class, () -> FileSystemSerDes.stageBuilder(basePath)
                .delegate(new JacksonSerDes()));
    }

    @Test
    void overflowModeKeepsSmallPayloadInlineAndWritesLargePayload() throws Exception {
        var serDes = FileSystemSerDes.builder(basePath)
                .storageMode(FileSystemStorageMode.OVERFLOW)
                .build();
        var runner = new SerDesRunner(null);

        var inline = runner.serialize(serDes, "small", context());
        assertTrue(MAPPER.readTree(inline).has("data"));

        var overflow = runner.serialize(serDes, "x".repeat(256 * 1024), context());
        assertTrue(MAPPER.readTree(overflow).has("file"));
    }

    @Test
    void immutableContentAddressedFilesPreservePriorCheckpoints() throws Exception {
        var serDes = FileSystemSerDes.stageBuilder(basePath).build();
        var runner = new SerDesRunner(null);

        var firstContext = context(1);
        var secondContext = context(2);
        var firstEnvelope = runner.serialize(serDes, "state-one", firstContext);
        var secondEnvelope = runner.serialize(serDes, "state-two", secondContext);
        var firstFile = Path.of(MAPPER.readTree(firstEnvelope).get("file").textValue());
        var secondFile = Path.of(MAPPER.readTree(secondEnvelope).get("file").textValue());

        assertNotEquals(firstFile, secondFile);
        assertEquals("state-one", Files.readString(firstFile));
        assertEquals("state-two", Files.readString(secondFile));
        assertEquals("state-one", runner.deserialize(serDes, firstEnvelope, TypeToken.get(String.class), firstContext));
    }

    @Test
    void hashEncodingUsesFixedLengthSegments() throws Exception {
        var serDes = FileSystemSerDes.builder(basePath)
                .pathEncoding(FileSystemPathEncoding.HASH)
                .build();

        var envelope = new SerDesRunner(null).serialize(serDes, "value", context());
        var file = Path.of(MAPPER.readTree(envelope).get("file").textValue());

        assertEquals(64, file.getParent().getFileName().toString().length());
        assertEquals(134, file.getFileName().toString().length());
        assertFalse(file.toString().contains("operation"));
    }

    @Test
    void includesBoundedPreviewWithoutChangingStoredPayload() throws Exception {
        var serDes = FileSystemSerDes.builder(basePath)
                .previewGenerator(value -> Map.of("summary", "order"))
                .build();
        var runner = new SerDesRunner(null);

        var envelope = runner.serialize(serDes, Map.of("secret", "value"), context());
        var json = MAPPER.readTree(envelope);

        assertEquals("order", json.get("preview").get("summary").textValue());
        assertEquals(
                "{\"secret\":\"value\"}",
                Files.readString(Path.of(json.get("file").textValue())));

        var oversizedPreview = FileSystemSerDes.builder(basePath)
                .previewGenerator(value -> Map.of("summary", "x".repeat(256 * 1024)))
                .build();
        var failure = assertThrows(SerDesException.class, () -> runner.serialize(oversizedPreview, "value", context()));
        assertTrue(failure.getCause().getMessage().contains("checkpoint payload limit"));
    }

    @Test
    void acceptsExternallyOriginatedRawPayloadsOnlyForSupportedSources() {
        var standalone = FileSystemSerDes.builder(basePath).build();
        var stage = FileSystemSerDes.stageBuilder(basePath).build();
        var runner = new SerDesRunner(null);

        assertEquals(
                Map.of("id", 42),
                runner.deserialize(
                        standalone,
                        "{\"id\":42}",
                        new TypeToken<Map<String, Integer>>() {},
                        executionContext(SerDesPayloadKind.INPUT)));
        assertEquals(
                "{\"id\":42}",
                runner.deserialize(
                        stage,
                        "{\"id\":42}",
                        TypeToken.get(String.class),
                        operationContext(OperationType.CALLBACK, OperationSubType.CALLBACK)));
        assertEquals(
                "\"invoke-result\"",
                runner.deserialize(
                        stage,
                        "\"invoke-result\"",
                        TypeToken.get(String.class),
                        operationContext(OperationType.CHAINED_INVOKE, OperationSubType.CHAINED_INVOKE)));

        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(stage, "\"raw-step\"", TypeToken.get(String.class), context()));
    }

    @Test
    void rejectsCallsWithoutContextAndMalformedOrUnsafeEnvelopes() throws Exception {
        var serDes = FileSystemSerDes.builder(basePath).build();
        assertThrows(SerDesException.class, () -> serDes.serialize("value"));

        var runner = new SerDesRunner(null);
        assertThrows(
                SerDesException.class, () -> runner.deserialize(serDes, "{}", TypeToken.get(String.class), context()));
        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(
                        serDes, envelopeWithFile("/outside/payload.json"), TypeToken.get(String.class), context()));

        var missingEnvelope = runner.serialize(serDes, "missing", context());
        var missingFile = payloadFile(missingEnvelope);
        assertTrue(Files.deleteIfExists(missingFile));
        var missingFileFailure = assertThrows(
                RetryableSerDesException.class,
                () -> runner.deserialize(serDes, missingEnvelope, TypeToken.get(String.class), context()));
        assertInstanceOf(RetryableSerDesException.class, missingFileFailure.getCause());
    }

    @Test
    void rejectsCrossEntityAndSymbolicLinkReferences() throws Exception {
        var serDes = FileSystemSerDes.stageBuilder(basePath).build();
        var envelope = new SerDesRunner(null).serialize(serDes, "payload", context());

        var otherEntity = SerDesContext.forOperation(
                ARN, "2", "other-step", null, OperationType.STEP, OperationSubType.STEP, SerDesPayloadKind.RESULT, 1);
        assertThrows(SerDesException.class, () -> new SerDesRunner(null)
                .deserialize(serDes, envelope, TypeToken.get(String.class), otherEntity));

        var file = payloadFile(envelope);
        var outside = Files.createTempFile(basePath.getParent(), "outside-payload-", ".json");
        Files.writeString(outside, "payload");
        Files.delete(file);
        Files.createSymbolicLink(file, outside);

        assertThrows(SerDesException.class, () -> new SerDesRunner(null)
                .deserialize(serDes, envelope, TypeToken.get(String.class), context()));
    }

    @Test
    void rejectsSymbolicLinkDirectoriesWhenWriting() throws Exception {
        var outside = Files.createTempDirectory(basePath.getParent(), "outside-payloads-");
        Files.createSymbolicLink(basePath.resolve("orders"), outside);
        var serDes = FileSystemSerDes.stageBuilder(basePath).build();

        assertThrows(SerDesException.class, () -> new SerDesRunner(null).serialize(serDes, "payload", context()));
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void rejectsExecutionPathsOutsideConfiguredBasePath() {
        var serDes = FileSystemSerDes.builder(basePath).build();
        var unsafeContext = SerDesContext.forOperation(
                "arn:aws:lambda:us-east-1:123456789012:function:..:1/durable-execution/../..",
                "1",
                "step",
                null,
                OperationType.STEP,
                OperationSubType.STEP,
                SerDesPayloadKind.RESULT,
                1);

        assertThrows(SerDesException.class, () -> new SerDesRunner(null).serialize(serDes, "value", unsafeContext));
    }

    private static String envelopeWithFile(String file) {
        try {
            return MAPPER.writeValueAsString(Map.of(ENVELOPE_MARKER, 1, "file", file));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Path payloadFile(String envelope) {
        try {
            return Path.of(MAPPER.readTree(envelope).get("file").textValue());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static SerDesContext context() {
        return context(1);
    }

    private static SerDesContext context(int attempt) {
        return SerDesContext.forOperation(
                ARN, "1", "step", null, OperationType.STEP, OperationSubType.STEP, SerDesPayloadKind.RESULT, attempt);
    }

    private static SerDesContext executionContext(SerDesPayloadKind payloadKind) {
        return SerDesContext.forExecution(ARN, "invocation-1", "execution-1", payloadKind);
    }

    private static SerDesContext operationContext(OperationType operationType, OperationSubType operationSubType) {
        return SerDesContext.forOperation(
                ARN, "1", "operation", null, operationType, operationSubType, SerDesPayloadKind.RESULT, null);
    }
}
