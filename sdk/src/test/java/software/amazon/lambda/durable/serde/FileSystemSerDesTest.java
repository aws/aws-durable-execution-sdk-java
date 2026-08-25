// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.retry.RetryStrategies;

class FileSystemSerDesTest {
    private static final String ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:orders:1/durable-execution/execution-1/invocation-1";
    private static final String ENVELOPE_MARKER = "__durable_execution_filesystem_serdes";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path basePath;

    @Test
    void writesValueCodecPayloadAndReplaysIt() throws Exception {
        var serDes = new JacksonSerDes().then(FileSystemSerDes.builder(basePath).build());
        var runner = new SerDesRunner(null);

        var envelope = runner.serialize(serDes, Map.of("id", 42), context());
        var json = MAPPER.readTree(envelope);
        var file = Path.of(json.get("file").textValue());

        assertEquals(1, json.get(ENVELOPE_MARKER).intValue());
        assertEquals("STRING", json.get("payloadType").textValue());
        assertTrue(file.startsWith(basePath.resolve("orders/execution-1/invocation-1")));
        assertEquals("{\"id\":42}", Files.readString(file));
        assertEquals(
                Map.of("id", 42),
                runner.deserialize(serDes, envelope, new TypeToken<Map<String, Integer>>() {}, context()));
    }

    @Test
    void isAStageThatCanBeFollowedByOtherStages() {
        var stage = FileSystemSerDes.builder(basePath).build();
        var pipeline = new JacksonSerDes().then(stage).then(wrappingStage());
        var runner = new SerDesRunner(null);

        assertFalse(SerDes.class.isAssignableFrom(FileSystemSerDes.class));
        var checkpoint = runner.serialize(pipeline, Map.of("id", 42), context());
        assertTrue(checkpoint.startsWith("<"));
        assertEquals(
                Map.of("id", 42),
                runner.deserialize(pipeline, checkpoint, new TypeToken<Map<String, Integer>>() {}, context()));
    }

    @Test
    void retryDecoratorComposesAsAFileSystemStage() {
        var stage = FileSystemSerDes.builder(basePath)
                .storageMode(FileSystemStorageMode.OVERFLOW)
                .build();
        var pipeline = new JacksonSerDes().then(new RetrySerDes(stage, RetryStrategies.Presets.NO_RETRY));
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
                .then(xorBinarySerDes((byte) 0x5A))
                .endWith(Base64StringBinaryCodec.INSTANCE)
                .build();
        var stage = FileSystemSerDes.builder(basePath).build();
        var pipeline = new JacksonSerDes().then(binaryStage).then(stage);
        var runner = new SerDesRunner(null);

        var envelope = runner.serialize(pipeline, Map.of("id", 42), context());
        var json = MAPPER.readTree(envelope);
        var file = Path.of(json.get("file").textValue());

        assertEquals("STRING", json.get("payloadType").textValue());
        assertEquals(
                Base64.getEncoder().encodeToString(xor("{\"id\":42}".getBytes(StandardCharsets.UTF_8), (byte) 0x5A)),
                Files.readString(file));
        assertEquals(
                Map.of("id", 42),
                runner.deserialize(pipeline, envelope, new TypeToken<Map<String, Integer>>() {}, context()));
    }

    @Test
    void overflowModeKeepsSmallPayloadInlineAndWritesLargePayload() throws Exception {
        var stage = FileSystemSerDes.builder(basePath)
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
    void immutableContentAddressedFilesPreservePriorCheckpoints() throws Exception {
        var serDes = stringCodec().then(FileSystemSerDes.builder(basePath).build());
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
    void rejectsUnexpectedContentInExistingContentAddressedFile() throws Exception {
        var serDes = stringCodec().then(FileSystemSerDes.builder(basePath).build());
        var runner = new SerDesRunner(null);
        var envelope = runner.serialize(serDes, "expected", context());
        var file = payloadFile(envelope);
        Files.writeString(file, "unexpected");

        var failure = assertThrows(SerDesException.class, () -> runner.serialize(serDes, "expected", context()));

        assertCauseMessage(failure, "contains unexpected data");
        assertEquals("unexpected", Files.readString(file));
    }

    @Test
    void publishesOnFileSystemsWithoutHardLinkSupport() throws Exception {
        var archive = basePath.resolve("payloads.zip");
        try (var fileSystem =
                FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
            var archiveBasePath = fileSystem.getPath("/payloads");
            var serDes =
                    stringCodec().then(FileSystemSerDes.builder(archiveBasePath).build());

            var envelope = new SerDesRunner(null).serialize(serDes, "expected", context());
            var file = fileSystem.getPath(MAPPER.readTree(envelope).get("file").textValue());
            var hash = HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256").digest("expected".getBytes(StandardCharsets.UTF_8)));
            var fileName = file.getFileName().toString();
            assertTrue(fileName.contains(hash));
            var hashEnd = fileName.indexOf(hash) + hash.length();
            var deterministicFile = file.resolveSibling(fileName.substring(0, hashEnd) + ".payload");

            assertEquals("expected", Files.readString(file));
            assertNotEquals(deterministicFile, file);
            assertFalse(Files.exists(deterministicFile));
            assertTrue(fileName.startsWith(
                    deterministicFile.getFileName().toString().replace(".payload", "-")));
            assertTrue(fileName.endsWith(".payload"));
        }
    }

    @Test
    void rejectsMalformedUtf8StringsAndFilePayloads() throws Exception {
        var serDes = stringCodec().then(FileSystemSerDes.builder(basePath).build());
        var runner = new SerDesRunner(null);

        assertThrows(SerDesException.class, () -> runner.serialize(serDes, "lone surrogate \uD800", context()));

        var envelope = runner.serialize(serDes, "valid", context());
        var malformed = new byte[] {(byte) 0xC3, (byte) 0x28};
        var malformedFile = contentAddressedPath(payloadFile(envelope), malformed);
        Files.write(malformedFile, malformed);
        var malformedEnvelope = (ObjectNode) MAPPER.readTree(envelope);
        malformedEnvelope.put("file", malformedFile.toString());

        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(serDes, malformedEnvelope.toString(), TypeToken.get(String.class), context()));
    }

    @Test
    void hashEncodingUsesFixedLengthSegments() throws Exception {
        var stage = FileSystemSerDes.builder(basePath)
                .pathEncoding(FileSystemPathEncoding.HASH)
                .build();
        var serDes = stringCodec().then(stage);

        var envelope = new SerDesRunner(null).serialize(serDes, "value", context());
        var file = Path.of(MAPPER.readTree(envelope).get("file").textValue());

        assertEquals(64, file.getParent().getFileName().toString().length());
        assertEquals(137, file.getFileName().toString().length());
        assertFalse(file.toString().contains("operation"));
    }

    @Test
    void includesBoundedPreviewWithoutChangingStoredPayload() throws Exception {
        var stage = FileSystemSerDes.builder(basePath)
                .previewGenerator(value -> Map.of("summary", "order"))
                .build();
        var serDes = new JacksonSerDes().then(stage);
        var runner = new SerDesRunner(null);

        var envelope = runner.serialize(serDes, Map.of("secret", "value"), context());
        var json = MAPPER.readTree(envelope);

        assertEquals("order", json.get("preview").get("summary").textValue());
        assertEquals(
                "{\"secret\":\"value\"}",
                Files.readString(Path.of(json.get("file").textValue())));

        var oversizedPreviewStage = FileSystemSerDes.builder(basePath)
                .previewGenerator(value -> Map.of("summary", "x".repeat(256 * 1024)))
                .build();
        var oversizedPreview = stringCodec().then(oversizedPreviewStage);
        var failure = assertThrows(SerDesException.class, () -> runner.serialize(oversizedPreview, "value", context()));
        assertCauseMessage(failure, "checkpoint payload limit");
    }

    @Test
    void acceptsExternallyOriginatedRawPayloadsOnlyForSupportedSources() {
        var stage = FileSystemSerDes.builder(basePath).build();
        var filesystemPipeline = new JacksonSerDes().then(stage);
        var pipeline = new JacksonSerDes().then(wrappingStage()).then(stage);
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

        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(filesystemPipeline, "\"raw-step\"", TypeToken.get(String.class), context()));
    }

    @Test
    void rejectsUnsupportedEnvelopeVersionsAtExternalBoundaries() {
        var serDes = stringCodec().then(FileSystemSerDes.builder(basePath).build());
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
                        stringCodec().then(FileSystemSerDes.builder(basePath).build()),
                        envelope,
                        TypeToken.get(String.class),
                        context()));
    }

    @Test
    void rejectsOutOfRangeEnvelopeVersionsWithoutTruncation() {
        var serDes = stringCodec().then(FileSystemSerDes.builder(basePath).build());
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
        var filesystem = FileSystemSerDes.builder(basePath)
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
        var serDes = new JacksonSerDes().then(FileSystemSerDes.builder(basePath).build());
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
        var stage = FileSystemSerDes.builder(basePath).build();
        var serDes = stringCodec().then(stage);
        assertThrows(SerDesException.class, () -> stage.serialize("value"));

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
        var serDes = stringCodec().then(FileSystemSerDes.builder(basePath).build());
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
        var serDes = stringCodec().then(FileSystemSerDes.builder(basePath).build());

        assertThrows(SerDesException.class, () -> new SerDesRunner(null).serialize(serDes, "payload", context()));
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void rejectsSymbolicLinkConfiguredBasePathAndAncestors() throws Exception {
        var outsideRoot = Files.createTempDirectory(basePath.getParent(), "outside-root-");
        var linkedRoot = basePath.resolve("linked-root");
        Files.createSymbolicLink(linkedRoot, outsideRoot);

        var rootSerDes = stringCodec().then(FileSystemSerDes.builder(linkedRoot).build());
        assertThrows(SerDesException.class, () -> new SerDesRunner(null).serialize(rootSerDes, "payload", context()));

        var outsideAncestor = Files.createTempDirectory(basePath.getParent(), "outside-ancestor-");
        var linkedAncestor = basePath.resolve("linked-ancestor");
        Files.createSymbolicLink(linkedAncestor, outsideAncestor);
        var nestedSerDes = stringCodec()
                .then(FileSystemSerDes.builder(linkedAncestor.resolve("payloads"))
                        .build());

        assertThrows(SerDesException.class, () -> new SerDesRunner(null).serialize(nestedSerDes, "payload", context()));
        assertFalse(Files.exists(outsideAncestor.resolve("payloads")));
    }

    @Test
    void rejectsExecutionPathsOutsideConfiguredBasePath() {
        var serDes = stringCodec().then(FileSystemSerDes.builder(basePath).build());
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
                    "STRING"));
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

    private static Path contentAddressedPath(Path original, byte[] data) throws Exception {
        var name = original.getFileName().toString();
        var suffix = ".payload";
        var hashStart = name.length() - suffix.length() - 64;
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        return original.resolveSibling(name.substring(0, hashStart) + hash + suffix);
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

    private static BinarySerDes xorBinarySerDes(byte key) {
        return new BinarySerDes() {
            @Override
            public byte[] serialize(byte[] value) {
                return xor(value, key);
            }

            @Override
            public byte[] deserialize(byte[] data) {
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
            public String serialize(String value) {
                return "<" + value + ">";
            }

            @Override
            public String deserialize(String data) {
                return data.substring(1, data.length() - 1);
            }
        };
    }
}
