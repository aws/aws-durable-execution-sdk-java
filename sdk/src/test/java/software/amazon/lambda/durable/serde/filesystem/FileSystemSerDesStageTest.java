// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.serde.Base64StringBinaryCodec;
import software.amazon.lambda.durable.serde.BinarySerDesStage;
import software.amazon.lambda.durable.serde.ComposableBinarySerDesStage;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.RetrySerDesStage;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.serde.SerDesPayloadKind;
import software.amazon.lambda.durable.serde.SerDesRunner;
import software.amazon.lambda.durable.serde.SerDesStage;
import software.amazon.lambda.durable.serde.Utf8StringBinaryCodec;

class FileSystemSerDesStageTest {
    private static final String ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:orders:1/durable-execution/execution-1/invocation-1";
    private static final String ENVELOPE_MARKER = "__durable_execution_filesystem_serdes";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path basePath;

    @Test
    void writesValueCodecPayloadAndReplaysIt() throws Exception {
        var serDes =
                new JacksonSerDes().then(FileSystemSerDesStage.builder(basePath).build());
        var runner = new SerDesRunner(null);

        var envelope = runner.serialize(serDes, Map.of("id", 42), context());
        var json = MAPPER.readTree(envelope);
        var file = Path.of(json.get("file").textValue());

        assertEquals(1, json.get(ENVELOPE_MARKER).intValue());
        assertEquals("STRING", json.get("payloadType").textValue());
        assertEquals(
                sha256("{\"id\":42}".getBytes(StandardCharsets.UTF_8)),
                json.get("payloadDigest").textValue());
        assertTrue(file.startsWith(basePath.resolve("orders/execution-1/invocation-1")));
        assertEquals("{\"id\":42}", Files.readString(file));
        assertEquals(
                Map.of("id", 42),
                runner.deserialize(serDes, envelope, new TypeToken<Map<String, Integer>>() {}, context()));
    }

    @Test
    void isAStageThatCanBeFollowedByOtherStages() {
        var stage = FileSystemSerDesStage.builder(basePath).build();
        var pipeline = new JacksonSerDes().then(stage).then(wrappingStage());
        var runner = new SerDesRunner(null);

        assertFalse(SerDes.class.isAssignableFrom(FileSystemSerDesStage.class));
        var checkpoint = runner.serialize(pipeline, Map.of("id", 42), context());
        assertTrue(checkpoint.startsWith("<"));
        assertEquals(
                Map.of("id", 42),
                runner.deserialize(pipeline, checkpoint, new TypeToken<Map<String, Integer>>() {}, context()));
    }

    @Test
    void retryDecoratorComposesAsAFileSystemStage() {
        var stage = FileSystemSerDesStage.builder(basePath)
                .storageMode(FileSystemStorageMode.OVERFLOW)
                .build();
        var pipeline = new JacksonSerDes().then(new RetrySerDesStage(stage, RetryStrategies.Presets.NO_RETRY));
        var runner = new SerDesRunner(null);

        var envelope = runner.serialize(pipeline, Map.of("id", 42), context());

        assertEquals(
                Map.of("id", 42),
                runner.deserialize(pipeline, envelope, new TypeToken<Map<String, Integer>>() {}, context()));
    }

    @Test
    void storesAndRestoresComposableBinaryOutput() throws Exception {
        var binaryStage = ComposableBinarySerDesStage.builder()
                .startWith(Utf8StringBinaryCodec.INSTANCE)
                .then(xorBinaryStage((byte) 0x5A))
                .endWith(Base64StringBinaryCodec.INSTANCE)
                .build();
        var stage = FileSystemSerDesStage.builder(basePath).build();
        var pipeline = new JacksonSerDes().then(binaryStage).then(stage);
        var runner = new SerDesRunner(null);

        var envelope = runner.serialize(pipeline, Map.of("id", 42), context());
        var json = MAPPER.readTree(envelope);
        var file = Path.of(json.get("file").textValue());

        assertEquals("STRING", json.get("payloadType").textValue());
        assertEquals(
                "__durable_execution_composable_binary_serdes:1:"
                        + Base64.getEncoder()
                                .encodeToString(xor("{\"id\":42}".getBytes(StandardCharsets.UTF_8), (byte) 0x5A)),
                Files.readString(file));
        assertEquals(
                Map.of("id", 42),
                runner.deserialize(pipeline, envelope, new TypeToken<Map<String, Integer>>() {}, context()));
    }

    @Test
    void overflowModeKeepsSmallPayloadInlineAndWritesLargePayload() throws Exception {
        var stage = FileSystemSerDesStage.builder(basePath)
                .storageMode(FileSystemStorageMode.OVERFLOW)
                .build();
        var serDes = stringCodec().then(stage);
        var runner = new SerDesRunner(null);

        var inline = runner.serialize(serDes, "small", context());
        assertTrue(MAPPER.readTree(inline).has("data"));

        var overflow = runner.serialize(serDes, "x".repeat(256 * 1024), context());
        assertTrue(MAPPER.readTree(overflow).has("file"));
    }

    @Test
    void checkpointEnvelopeLimitCanBeIncreasedForLargerInlinePayloads() throws Exception {
        var value = "x".repeat(300 * 1024);
        var runner = new SerDesRunner(null);
        var defaultPipeline = stringCodec()
                .then(FileSystemSerDesStage.builder(basePath)
                        .storageMode(FileSystemStorageMode.OVERFLOW)
                        .build());
        var largerEnvelopePipeline = stringCodec()
                .then(FileSystemSerDesStage.builder(basePath)
                        .storageMode(FileSystemStorageMode.OVERFLOW)
                        .checkpointEnvelopeLimitBytes(512 * 1024)
                        .build());

        assertTrue(MAPPER.readTree(runner.serialize(defaultPipeline, value, context()))
                .has("file"));
        assertTrue(MAPPER.readTree(runner.serialize(largerEnvelopePipeline, value, context()))
                .has("data"));
    }

    @Test
    void checkpointEnvelopeLimitMustBePositive() {
        var zeroFailure = assertThrows(IllegalArgumentException.class, () -> FileSystemSerDesStage.builder(basePath)
                .checkpointEnvelopeLimitBytes(0));
        var negativeFailure = assertThrows(IllegalArgumentException.class, () -> FileSystemSerDesStage.builder(basePath)
                .checkpointEnvelopeLimitBytes(-1));

        assertEquals("checkpointEnvelopeLimitBytes must be positive", zeroFailure.getMessage());
        assertEquals("checkpointEnvelopeLimitBytes must be positive", negativeFailure.getMessage());
    }

    @Test
    void checkpointEnvelopeLimitAlsoAppliesToFileEnvelopes() {
        var pipeline = stringCodec()
                .then(FileSystemSerDesStage.builder(basePath)
                        .checkpointEnvelopeLimitBytes(1)
                        .build());

        var failure = assertThrows(
                SerDesException.class, () -> new SerDesRunner(null).serialize(pipeline, "value", context()));

        assertCauseMessage(failure, "checkpoint payload limit");
    }

    @Test
    void immutableContentAddressedFilesPreservePriorCheckpoints() throws Exception {
        var serDes = stringCodec().then(FileSystemSerDesStage.builder(basePath).build());
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
    void repeatedPayloadsUseDistinctImmutableFiles() throws Exception {
        var serDes = stringCodec().then(FileSystemSerDesStage.builder(basePath).build());
        var runner = new SerDesRunner(null);
        var firstEnvelope = runner.serialize(serDes, "expected", context());
        var secondEnvelope = runner.serialize(serDes, "expected", context());
        var firstFile = payloadFile(firstEnvelope);
        var secondFile = payloadFile(secondEnvelope);

        assertNotEquals(firstFile, secondFile);
        assertEquals("expected", Files.readString(firstFile));
        assertEquals("expected", Files.readString(secondFile));
    }

    @Test
    void includesPayloadDigestAndVerifiesFileIntegrity() throws Exception {
        var serDes = stringCodec().then(FileSystemSerDesStage.builder(basePath).build());
        var runner = new SerDesRunner(null);
        var envelope = runner.serialize(serDes, "expected", context());
        var json = (ObjectNode) MAPPER.readTree(envelope);
        var expectedDigest = sha256("expected".getBytes(StandardCharsets.UTF_8));

        assertEquals(expectedDigest, json.get("payloadDigest").textValue());

        var tampered = "tampered".getBytes(StandardCharsets.UTF_8);
        Files.write(payloadFile(envelope), tampered);

        var failure = assertThrows(
                SerDesException.class,
                () -> runner.deserialize(serDes, json.toString(), TypeToken.get(String.class), context()));
        assertCauseMessage(failure, "payload digest does not match stored content");
    }

    @Test
    void verifiesFilePathUsesEnvelopePayloadDigest() throws Exception {
        var serDes = stringCodec().then(FileSystemSerDesStage.builder(basePath).build());
        var runner = new SerDesRunner(null);
        var envelope = runner.serialize(serDes, "expected", context());
        var json = (ObjectNode) MAPPER.readTree(envelope);
        var tampered = "tampered".getBytes(StandardCharsets.UTF_8);
        var tamperedFile = contentHashedPath(payloadFile(envelope), tampered);
        Files.write(tamperedFile, tampered);
        json.put("file", tamperedFile.toString());

        var failure = assertThrows(
                SerDesException.class,
                () -> runner.deserialize(serDes, json.toString(), TypeToken.get(String.class), context()));
        assertCauseMessage(failure, "file path does not match its payload digest");
    }

    @Test
    void includesPayloadDigestAndVerifiesInlineIntegrity() throws Exception {
        var serDes = stringCodec()
                .then(FileSystemSerDesStage.builder(basePath)
                        .storageMode(FileSystemStorageMode.OVERFLOW)
                        .build());
        var runner = new SerDesRunner(null);
        var envelope = (ObjectNode) MAPPER.readTree(runner.serialize(serDes, "expected", context()));

        assertEquals(
                sha256("expected".getBytes(StandardCharsets.UTF_8)),
                envelope.get("payloadDigest").textValue());

        envelope.put("data", "tampered");
        var failure = assertThrows(
                SerDesException.class,
                () -> runner.deserialize(serDes, envelope.toString(), TypeToken.get(String.class), context()));
        assertCauseMessage(failure, "payload digest does not match stored content");
    }

    @Test
    void rejectsMissingOrMalformedPayloadDigest() throws Exception {
        var serDes = stringCodec().then(FileSystemSerDesStage.builder(basePath).build());
        var runner = new SerDesRunner(null);
        var envelope = (ObjectNode) MAPPER.readTree(runner.serialize(serDes, "expected", context()));

        envelope.remove("payloadDigest");
        var missingFailure = assertThrows(
                SerDesException.class,
                () -> runner.deserialize(serDes, envelope.toString(), TypeToken.get(String.class), context()));
        assertCauseMessage(missingFailure, "Invalid filesystem SerDes envelope");

        envelope.put("payloadDigest", "not-a-sha-256-digest");
        var malformedFailure = assertThrows(
                SerDesException.class,
                () -> runner.deserialize(serDes, envelope.toString(), TypeToken.get(String.class), context()));
        assertCauseMessage(malformedFailure, "Invalid filesystem SerDes envelope");
    }

    @Test
    void failsClosedWhenTheFileSystemProviderLacksSecureDirectoryStreams() throws Exception {
        var archive = basePath.resolve("payloads.zip");
        try (var fileSystem =
                FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
            var archiveBasePath = fileSystem.getPath("/payloads");
            var serDes = stringCodec()
                    .then(FileSystemSerDesStage.builder(archiveBasePath).build());

            var failure = assertThrows(
                    SerDesException.class, () -> new SerDesRunner(null).serialize(serDes, "expected", context()));

            assertCauseMessage(failure, "SecureDirectoryStream support");
        }
    }

    @Test
    void rejectsMalformedUtf8StringsAndFilePayloads() throws Exception {
        var serDes = stringCodec().then(FileSystemSerDesStage.builder(basePath).build());
        var runner = new SerDesRunner(null);

        assertThrows(SerDesException.class, () -> runner.serialize(serDes, "lone surrogate \uD800", context()));

        var envelope = runner.serialize(serDes, "valid", context());
        var malformed = new byte[] {(byte) 0xC3, (byte) 0x28};
        var malformedFile = contentHashedPath(payloadFile(envelope), malformed);
        Files.write(malformedFile, malformed);
        var malformedEnvelope = (ObjectNode) MAPPER.readTree(envelope);
        malformedEnvelope.put("file", malformedFile.toString());
        malformedEnvelope.put("payloadDigest", sha256(malformed));

        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(serDes, malformedEnvelope.toString(), TypeToken.get(String.class), context()));
    }

    @Test
    void hashEncodingUsesFixedLengthSegments() throws Exception {
        var stage = FileSystemSerDesStage.builder(basePath)
                .pathEncoding(FileSystemPathEncoding.HASH)
                .build();
        var serDes = stringCodec().then(stage);

        var envelope = new SerDesRunner(null).serialize(serDes, "value", context());
        var file = Path.of(MAPPER.readTree(envelope).get("file").textValue());

        assertEquals(64, file.getParent().getFileName().toString().length());
        assertEquals(174, file.getFileName().toString().length());
        assertTrue(file.getFileName().toString().matches("[0-9a-f]{64}-[0-9a-f]{64}-[0-9a-f-]{36}\\.payload"));
        assertFalse(file.toString().contains("operation"));
    }

    @Test
    void includesBoundedPreviewWithoutChangingStoredPayload() throws Exception {
        var previewValue = new AtomicReference<String>();
        var previewContext = new AtomicReference<SerDesContext>();
        var stage = FileSystemSerDesStage.builder(basePath)
                .previewGenerator((value, context) -> {
                    previewValue.set(value);
                    previewContext.set(context);
                    return Map.of("summary", "order");
                })
                .build();
        var serDes = new JacksonSerDes().then(stage);
        var runner = new SerDesRunner(null);
        var originalValue = Map.of("secret", "value");

        var envelope = runner.serialize(serDes, originalValue, context());
        var json = MAPPER.readTree(envelope);

        assertEquals("order", json.get("preview").get("summary").textValue());
        assertEquals("{\"secret\":\"value\"}", previewValue.get());
        assertSame(originalValue, previewContext.get().originalValue());
        assertEquals(
                "{\"secret\":\"value\"}",
                Files.readString(Path.of(json.get("file").textValue())));

        var oversizedPreviewStage = FileSystemSerDesStage.builder(basePath)
                .previewGenerator((value, context) -> Map.of("summary", "x".repeat(256 * 1024)))
                .build();
        var oversizedPreview = stringCodec().then(oversizedPreviewStage);
        var failure = assertThrows(SerDesException.class, () -> runner.serialize(oversizedPreview, "value", context()));
        assertCauseMessage(failure, "checkpoint payload limit");
    }

    @Test
    void retryablePreviewFailureCanBeRetried() throws Exception {
        var attempts = new AtomicInteger();
        var fileSystemStage = FileSystemSerDesStage.builder(basePath)
                .previewGenerator((value, context) -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new RetryableSerDesException("preview service unavailable");
                    }
                    return Map.of("summary", "order");
                })
                .build();
        var pipeline = stringCodec()
                .then(new RetrySerDesStage(
                        fileSystemStage,
                        (failure, attempt) ->
                                attempt == 1 ? RetryDecision.retry(Duration.ZERO) : RetryDecision.fail()));

        var envelope = new SerDesRunner(null).serialize(pipeline, "value", context());

        assertEquals(2, attempts.get());
        assertEquals(
                "order", MAPPER.readTree(envelope).get("preview").get("summary").textValue());
    }

    @Test
    void structuredPreviewConfigSelectsAndMasksJsonFields() throws Exception {
        var stage = FileSystemSerDesStage.builder(basePath)
                .previewConfig(PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
                        .include(PreviewField.anywhere("id"), PreviewField.path("customer.status"))
                        .mask(PreviewField.anywhere("email"))
                        .build())
                .build();
        var serDes = new JacksonSerDes().then(stage);
        var runner = new SerDesRunner(null);

        var value = Map.of(
                "id",
                "order-1",
                "email",
                "root@example.com",
                "customer",
                Map.of("status", "ready", "email", "customer@example.com", "secret", "hidden"));
        var envelope = runner.serialize(serDes, value, context());
        var preview = MAPPER.readTree(envelope).get("preview");

        assertEquals("order-1", preview.get("id").textValue());
        assertEquals("***", preview.get("email").textValue());
        assertEquals("ready", preview.get("customer").get("status").textValue());
        assertEquals("***", preview.get("customer").get("email").textValue());
        assertFalse(preview.get("customer").has("secret"));
        assertEquals(value, runner.deserialize(serDes, envelope, new TypeToken<Map<String, Object>>() {}, context()));
    }

    @Test
    void structuredPreviewConfigRequiresJsonStageValue() {
        var stage = FileSystemSerDesStage.builder(basePath)
                .previewConfig(PreviewConfig.builder(PreviewMode.INCLUDE_ALL).build())
                .build();
        var runner = new SerDesRunner(null);

        var failure = assertThrows(
                SerDesException.class, () -> runner.serialize(stringCodec().then(stage), "not-json", context()));

        assertCauseMessage(failure, "requires a JSON stage value");
    }

    @Test
    void passesUnrecognizedPayloadsThroughAtEverySource() {
        var stage = FileSystemSerDesStage.builder(basePath).build();
        var filesystemPipeline = new JacksonSerDes().then(stage);
        var pipeline = new JacksonSerDes().then(wrappingStage()).then(stage);
        var pipelineWithStageAfterFilesystem = new JacksonSerDes().then(stage).then(wrappingStage());
        var runner = new SerDesRunner(null);

        assertEquals(
                Map.of("id", 42),
                runner.deserialize(
                        filesystemPipeline,
                        "{\"id\":42}",
                        new TypeToken<Map<String, Integer>>() {},
                        executionContext(SerDesPayloadKind.INPUT)));
        assertEquals(
                Map.of("id", 42),
                runner.deserialize(
                        pipeline,
                        "{\"id\":42}",
                        new TypeToken<Map<String, Integer>>() {},
                        operationContext(OperationType.CALLBACK, OperationSubType.CALLBACK)));
        assertEquals(
                "invoke-result",
                runner.deserialize(
                        pipeline,
                        "\"invoke-result\"",
                        TypeToken.get(String.class),
                        operationContext(OperationType.CHAINED_INVOKE, OperationSubType.CHAINED_INVOKE)));
        assertEquals(
                Map.of("id", 42),
                runner.deserialize(
                        pipelineWithStageAfterFilesystem,
                        "{\"id\":42}",
                        new TypeToken<Map<String, Integer>>() {},
                        executionContext(SerDesPayloadKind.INPUT)));
        assertEquals(
                Map.of("domainMarker", 1, "data", "domain-value"),
                runner.deserialize(
                        filesystemPipeline,
                        "{\"domainMarker\":1,\"data\":\"domain-value\"}",
                        new TypeToken<Map<String, Object>>() {},
                        executionContext(SerDesPayloadKind.INPUT)));
        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(
                        filesystemPipeline,
                        "{\"__durable_execution_filesystem_serdes\":1,\"data\":\"domain-value\"}",
                        new TypeToken<Map<String, Object>>() {},
                        executionContext(SerDesPayloadKind.INPUT)));

        assertEquals(
                "raw-step",
                runner.deserialize(filesystemPipeline, "\"raw-step\"", TypeToken.get(String.class), context()));
    }

    @Test
    void rejectsTrailingTokensAfterFilesystemEnvelope() {
        var stage = FileSystemSerDesStage.builder(basePath).build();
        var envelope = "{\"__durable_execution_filesystem_serdes\":1,"
                + "\"ownerDurableExecutionArn\":\""
                + ARN
                + "\",\"ownerEntityId\":\"1\",\"payloadType\":\"STRING\","
                + "\"payloadDigest\":\""
                + sha256("value".getBytes(StandardCharsets.UTF_8))
                + "\",\"data\":\"value\"}";

        var failure = assertThrows(SerDesException.class, () -> stage.deserialize(envelope + " true", context()));

        assertCauseMessage(failure, "Invalid filesystem SerDes envelope");
    }

    @Test
    void rejectsDuplicateFilesystemEnvelopeFields() throws Exception {
        var serDes = stringCodec().then(FileSystemSerDesStage.builder(basePath).build());
        var runner = new SerDesRunner(null);
        var envelope = runner.serialize(serDes, "value", context());
        var duplicateValues = Map.<String, Object>of(
                ENVELOPE_MARKER,
                2,
                "ownerDurableExecutionArn",
                "other-arn",
                "ownerEntityId",
                "other-entity",
                "payloadType",
                "BYTES",
                "payloadDigest",
                "0".repeat(64),
                "file",
                basePath.resolve("other.payload").toString());

        for (var duplicate : duplicateValues.entrySet()) {
            var duplicateEnvelope = withDuplicateField(envelope, duplicate.getKey(), duplicate.getValue());

            var failure = assertThrows(
                    SerDesException.class,
                    () -> runner.deserialize(serDes, duplicateEnvelope, TypeToken.get(String.class), context()));

            assertCauseMessage(failure, "Invalid filesystem SerDes envelope");
        }
    }

    @Test
    void duplicateFieldsInUnmarkedJsonPassThrough() {
        var stage = FileSystemSerDesStage.builder(basePath).build();
        var value = "{\"id\":\"first\",\"id\":\"second\"}";

        assertEquals(value, stage.deserialize(value, null));
    }

    @Test
    void recognizesMalformedFilesystemMarkerRegardlessOfWhitespaceOrFieldOrder() {
        var stage = FileSystemSerDesStage.builder(basePath).build();
        var malformedEnvelopes = List.of(
                "{ \n  \"__durable_execution_filesystem_serdes\" : 1",
                "{\"precedingField\":true,\n  \"__durable_execution_filesystem_serdes\" : 1",
                "{\"\\u005f_durable_execution_filesystem_serdes\" : 1");

        for (var envelope : malformedEnvelopes) {
            var failure = assertThrows(SerDesException.class, () -> stage.deserialize(envelope, context()));
            assertCauseMessage(failure, "Invalid filesystem SerDes envelope");
        }
    }

    @Test
    void doesNotTreatFilesystemMarkerTextInsideAStringAsAnEnvelope() {
        var stage = FileSystemSerDesStage.builder(basePath).build();
        var value = "{\"message\":\"__durable_execution_filesystem_serdes\"} trailing";

        assertEquals(value, stage.deserialize(value, null));
    }

    @Test
    void rejectsUnsupportedEnvelopeVersionsAtExternalBoundaries() {
        var serDes = stringCodec().then(FileSystemSerDesStage.builder(basePath).build());
        var futureEnvelope = "{\"__durable_execution_filesystem_serdes\":2,"
                + "\"ownerDurableExecutionArn\":\""
                + ARN
                + "\",\"ownerEntityId\":\"1\",\"data\":\"value\"}";

        var failure = assertThrows(SerDesException.class, () -> new SerDesRunner(null)
                .deserialize(
                        serDes,
                        futureEnvelope,
                        TypeToken.get(String.class),
                        executionContext(SerDesPayloadKind.INPUT)));

        assertCauseMessage(failure, "Unsupported filesystem SerDes envelope version 2");
    }

    @Test
    void rejectsUnsupportedBinaryPayloadType() {
        var envelope = "{\"__durable_execution_filesystem_serdes\":1,"
                + "\"ownerDurableExecutionArn\":\""
                + ARN
                + "\",\"ownerEntityId\":\"1\",\"payloadType\":\"BYTES\",\"data\":\"not-base64!\"}";

        assertThrows(SerDesException.class, () -> new SerDesRunner(null)
                .deserialize(
                        stringCodec()
                                .then(FileSystemSerDesStage.builder(basePath).build()),
                        envelope,
                        TypeToken.get(String.class),
                        context()));
    }

    @Test
    void rejectsOutOfRangeEnvelopeVersionsWithoutTruncation() {
        var serDes = stringCodec().then(FileSystemSerDesStage.builder(basePath).build());
        var oversizedVersion = "{\"__durable_execution_filesystem_serdes\":4294967297,"
                + "\"ownerDurableExecutionArn\":\""
                + ARN
                + "\",\"ownerEntityId\":\"1\",\"data\":\"value\"}";

        var failure = assertThrows(SerDesException.class, () -> new SerDesRunner(null)
                .deserialize(
                        serDes,
                        oversizedVersion,
                        TypeToken.get(String.class),
                        executionContext(SerDesPayloadKind.INPUT)));

        assertCauseMessage(failure, "Unsupported filesystem SerDes envelope version 4294967297");
    }

    @Test
    void overflowFilesystemStageCanBeFollowedByAnotherStage() {
        var filesystem = FileSystemSerDesStage.builder(basePath)
                .storageMode(FileSystemStorageMode.OVERFLOW)
                .build();
        var pipeline = stringCodec().then(filesystem).then(wrappingStage());
        var runner = new SerDesRunner(null);

        var checkpoint = runner.serialize(pipeline, "small", context());

        assertTrue(checkpoint.startsWith("<"));
        assertEquals("small", runner.deserialize(pipeline, checkpoint, TypeToken.get(String.class), context()));
    }

    @Test
    void fileReferencesCrossInvokeInputAndResultBoundaries() {
        var serDes =
                new JacksonSerDes().then(FileSystemSerDesStage.builder(basePath).build());
        var runner = new SerDesRunner(null);
        var callerArn =
                "arn:aws:lambda:us-east-1:123456789012:function:caller:1/durable-execution/caller-execution/caller-invocation";
        var calleeArn =
                "arn:aws:lambda:us-east-1:123456789012:function:callee:1/durable-execution/callee-execution/callee-invocation";
        var callerInvokePayload = SerDesContext.forOperation(
                callerArn,
                "invoke-1",
                "call-callee",
                null,
                OperationType.CHAINED_INVOKE,
                OperationSubType.CHAINED_INVOKE,
                SerDesPayloadKind.INVOKE_PAYLOAD,
                null);
        var calleeInput =
                SerDesContext.forExecution(calleeArn, "callee-invocation", "callee-execution", SerDesPayloadKind.INPUT);

        var invokeEnvelope = runner.serialize(serDes, Map.of("request", "value"), callerInvokePayload);
        assertEquals(
                Map.of("request", "value"),
                runner.deserialize(serDes, invokeEnvelope, new TypeToken<Map<String, String>>() {}, calleeInput));

        var calleeOutput = SerDesContext.forExecution(
                calleeArn, "callee-invocation", "callee-execution", SerDesPayloadKind.OUTPUT);
        var callerInvokeResult = SerDesContext.forOperation(
                callerArn,
                "invoke-1",
                "call-callee",
                null,
                OperationType.CHAINED_INVOKE,
                OperationSubType.CHAINED_INVOKE,
                SerDesPayloadKind.RESULT,
                null);
        var resultEnvelope = runner.serialize(serDes, Map.of("response", "value"), calleeOutput);

        assertEquals(
                Map.of("response", "value"),
                runner.deserialize(
                        serDes, resultEnvelope, new TypeToken<Map<String, String>>() {}, callerInvokeResult));
    }

    @Test
    void rejectsCallsWithoutContextAndMalformedOrUnsafeEnvelopes() throws Exception {
        var stage = FileSystemSerDesStage.builder(basePath).build();
        var serDes = stringCodec().then(stage);
        assertThrows(SerDesException.class, () -> stage.serialize("value", null));
        assertEquals("value", stage.deserialize("value", null));

        var runner = new SerDesRunner(null);
        assertEquals("{}", runner.deserialize(serDes, "{}", TypeToken.get(String.class), context()));
        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(
                        serDes, "{\"__durable_execution_filesystem_serdes\":", TypeToken.get(String.class), context()));
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
        var serDes = stringCodec().then(FileSystemSerDesStage.builder(basePath).build());
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
        var serDes = stringCodec().then(FileSystemSerDesStage.builder(basePath).build());

        assertThrows(SerDesException.class, () -> new SerDesRunner(null).serialize(serDes, "payload", context()));
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void rejectsSymbolicLinkDirectoriesWhenReading() throws Exception {
        var serDes = stringCodec().then(FileSystemSerDesStage.builder(basePath).build());
        var runner = new SerDesRunner(null);
        var envelope = runner.serialize(serDes, "payload", context());
        var orders = basePath.resolve("orders");
        var outside = Files.createTempDirectory(basePath.getParent(), "outside-payloads-");
        var outsideOrders = outside.resolve("orders");
        Files.move(orders, outsideOrders);
        Files.createSymbolicLink(orders, outsideOrders);

        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(serDes, envelope, TypeToken.get(String.class), context()));
    }

    @Test
    void rejectsSymbolicLinkConfiguredBasePathAndAncestors() throws Exception {
        var outsideRoot = Files.createTempDirectory(basePath.getParent(), "outside-root-");
        var linkedRoot = basePath.resolve("linked-root");
        Files.createSymbolicLink(linkedRoot, outsideRoot);

        var rootSerDes =
                stringCodec().then(FileSystemSerDesStage.builder(linkedRoot).build());
        assertThrows(SerDesException.class, () -> new SerDesRunner(null).serialize(rootSerDes, "payload", context()));

        var outsideAncestor = Files.createTempDirectory(basePath.getParent(), "outside-ancestor-");
        var linkedAncestor = basePath.resolve("linked-ancestor");
        Files.createSymbolicLink(linkedAncestor, outsideAncestor);
        var nestedSerDes = stringCodec()
                .then(FileSystemSerDesStage.builder(linkedAncestor.resolve("payloads"))
                        .build());

        assertThrows(SerDesException.class, () -> new SerDesRunner(null).serialize(nestedSerDes, "payload", context()));
        assertFalse(Files.exists(outsideAncestor.resolve("payloads")));
    }

    @Test
    void rejectsExecutionPathsOutsideConfiguredBasePath() {
        var serDes = stringCodec().then(FileSystemSerDesStage.builder(basePath).build());
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

    private static SerDes stringCodec() {
        return new SerDes() {
            @Override
            public String serialize(Object value) {
                return (String) value;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                if (!TypeToken.get(String.class).equals(typeToken)) {
                    throw new SerDesException("String codec cannot deserialize " + typeToken);
                }
                return (T) data;
            }
        };
    }

    private static void assertCauseMessage(Throwable failure, String expected) {
        var current = failure;
        while (current != null
                && (current.getMessage() == null || !current.getMessage().contains(expected))) {
            current = current.getCause();
        }
        assertNotNull(current, "Expected exception chain to contain: " + expected);
    }

    private static String withDuplicateField(String envelope, String field, Object value) throws Exception {
        return "{"
                + MAPPER.writeValueAsString(field)
                + ":"
                + MAPPER.writeValueAsString(value)
                + ","
                + envelope.substring(1);
    }

    private static String envelopeWithFile(String file) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    ENVELOPE_MARKER,
                    1,
                    "file",
                    file,
                    "ownerDurableExecutionArn",
                    ARN,
                    "ownerEntityId",
                    "1",
                    "payloadType",
                    "STRING",
                    "payloadDigest",
                    "0".repeat(64)));
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

    private static Path contentHashedPath(Path original, byte[] data) throws Exception {
        var name = original.getFileName().toString();
        var existingHash = sha256(Files.readAllBytes(original));
        var hash = sha256(data);
        return original.resolveSibling(name.replace(existingHash, hash));
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
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

    private static BinarySerDesStage xorBinaryStage(byte key) {
        return new BinarySerDesStage() {
            @Override
            public byte[] serialize(byte[] value, SerDesContext context) {
                return xor(value, key);
            }

            @Override
            public byte[] deserialize(byte[] data, SerDesContext context) {
                return xor(data, key);
            }
        };
    }

    private static byte[] xor(byte[] value, byte key) {
        var result = Arrays.copyOf(value, value.length);
        for (int index = 0; index < result.length; index++) {
            result[index] ^= key;
        }
        return result;
    }

    private static SerDesStage wrappingStage() {
        return new SerDesStage() {
            @Override
            public String serialize(String value, SerDesContext context) {
                return "<" + value + ">";
            }

            @Override
            public String deserialize(String data, SerDesContext context) {
                if (!data.startsWith("<")) {
                    return data;
                }
                if (!data.endsWith(">")) {
                    throw new SerDesException("Malformed wrapping stage value");
                }
                return data.substring(1, data.length() - 1);
            }
        };
    }
}
