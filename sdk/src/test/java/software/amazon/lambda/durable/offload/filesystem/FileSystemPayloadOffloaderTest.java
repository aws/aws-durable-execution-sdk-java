// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.lambda.durable.exception.PayloadOffloadException;
import software.amazon.lambda.durable.exception.RetryablePayloadOffloadException;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.offload.OffloadedPayload;
import software.amazon.lambda.durable.offload.PayloadOffloadContext;
import software.amazon.lambda.durable.offload.PayloadStorageMode;
import software.amazon.lambda.durable.offload.SerDesPayloadKind;

class FileSystemPayloadOffloaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void alwaysModeWritesAndLoadsPayloadUsingReadablePath() {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory).build();

        var payload = offloader.offload("{\"value\":\"stored\"}", context());

        assertEquals(PayloadStorageMode.REFERENCE, payload.mode());
        assertTrue(Path.of(payload.reference()).getFileName().toString().startsWith("operation%2Fop%2F1%2Fresult"));
        assertTrue(Files.exists(Path.of(payload.reference())));
        assertEquals("{\"value\":\"stored\"}", offloader.load(payload, context()));
    }

    @Test
    void overflowModeKeepsSmallPayloadInlineAndOffloadsLargePayload() {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory)
                .storageMode(PayloadOffloadMode.OVERFLOW)
                .build();

        var inline = offloader.offload("small", context());
        var reference = offloader.offload("x".repeat(256 * 1024), context());

        assertEquals(PayloadStorageMode.INLINE, inline.mode());
        assertEquals("small", inline.data());
        assertEquals(PayloadStorageMode.REFERENCE, reference.mode());
    }

    @Test
    void overflowModeAccountsForEnvelopeEscaping() {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory)
                .storageMode(PayloadOffloadMode.OVERFLOW)
                .build();
        var highlyEscapablePayload = "\"".repeat(128 * 1024);

        var payload = offloader.offload(highlyEscapablePayload, context());

        assertTrue(highlyEscapablePayload.getBytes(StandardCharsets.UTF_8).length < 255 * 1024);
        assertEquals(PayloadStorageMode.REFERENCE, payload.mode());
    }

    @Test
    void checkpointEnvelopeLimitRejectsValuesAboveServiceSafeMaximum() {
        assertThrows(IllegalArgumentException.class, () -> FileSystemPayloadOffloader.builder(temporaryDirectory)
                .checkpointEnvelopeLimitBytes(256 * 1024 - 1024 + 1));
    }

    @Test
    void hashModeUsesFixedLengthFilesystemSafeNames() {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory)
                .pathEncoding(FileSystemPathEncoding.HASH)
                .build();

        var payload = offloader.offload("stored", context());
        var path = Path.of(payload.reference());

        assertEquals(174, path.getFileName().toString().length());
        assertEquals(temporaryDirectory, path.getParent());
    }

    @Test
    void previewMetadataIsCopiedIntoEnvelope() {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory)
                .previewGenerator((serialized, context) -> Map.of("entity", context.entityId()))
                .build();

        var payload = offloader.offload("stored", context());

        assertEquals(context().entityId(), payload.preview().get("entity"));
    }

    @Test
    void structuredPreviewSupportsSelectionAndMasking() {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory)
                .previewConfig(PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
                        .include(PreviewField.anywhere("id"))
                        .mask(PreviewField.anywhere("email"))
                        .build())
                .build();
        var previewContext = context().withOriginalValue(Map.of("id", "123", "email", "secret@example.com"));

        var payload = offloader.offload("{\"id\":\"123\",\"email\":\"secret@example.com\"}", previewContext);

        assertEquals("123", payload.preview().get("id"));
        assertEquals("***", payload.preview().get("email"));
    }

    @Test
    void structuredPreviewUsesSerializedValueInsteadOfOriginalObject() {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory)
                .previewConfig(PreviewConfig.builder(PreviewMode.INCLUDE_ALL).build())
                .build();
        var previewContext = context().withOriginalValue(Map.of("visible", "value", "secret", "plaintext"));

        var payload = offloader.offload("{\"visible\":\"value\"}", previewContext);

        assertEquals(Map.of("visible", "value"), payload.preview());
    }

    @Test
    void customPreviewGeneratorCanExplicitlyUseOriginalObject() {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory)
                .previewGenerator((serialized, context) -> {
                    @SuppressWarnings("unchecked")
                    var original = (Map<String, Object>) context.originalValue();
                    return Map.of("secret", original.get("secret"));
                })
                .build();
        var previewContext = context().withOriginalValue(Map.of("secret", "plaintext"));

        var payload = offloader.offload("{\"visible\":\"value\"}", previewContext);

        assertEquals("plaintext", payload.preview().get("secret"));
    }

    @Test
    void oversizedReferencePreviewFailsBeforePublishingFile() throws Exception {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory)
                .checkpointEnvelopeLimitBytes(512)
                .previewGenerator((serialized, context) -> Map.of("large", "x".repeat(1024)))
                .build();

        assertThrows(PayloadOffloadException.class, () -> offloader.offload("stored", context()));
        try (var files = Files.walk(temporaryDirectory)) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void repeatedWritesPublishImmutableFiles() {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory).build();

        var first = offloader.offload("first", context());
        var second = offloader.offload("second", context());

        assertNotEquals(first.reference(), second.reference());
        assertEquals("first", offloader.load(first, context()));
        assertEquals("second", offloader.load(second, context()));
    }

    @Test
    void loadRejectsReferencesOutsideConfiguredBasePath() {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory).build();
        var outside = temporaryDirectory.getParent().resolve("outside.json");
        var validPayload = offloader.offload("stored", context());
        var payload = OffloadedPayload.reference(
                outside.toString(),
                null,
                validPayload.ownerDurableExecutionArn(),
                validPayload.ownerEntityId(),
                validPayload.payloadDigest());

        var failure = assertThrows(PayloadOffloadException.class, () -> offloader.load(payload, context()));

        assertFalse(failure instanceof RetryablePayloadOffloadException);
    }

    @Test
    void loadRejectsSymbolicLinksOutsideConfiguredBasePath() throws Exception {
        var basePath = temporaryDirectory.resolve("base");
        var outsideFile = temporaryDirectory.resolve("outside.json");
        Files.createDirectories(basePath);
        Files.writeString(outsideFile, "outside");
        var offloader = FileSystemPayloadOffloader.builder(basePath).build();
        var payload = offloader.offload("stored", context());
        var link = Path.of(payload.reference());
        Files.delete(link);
        Files.createSymbolicLink(link, outsideFile);

        var failure = assertThrows(PayloadOffloadException.class, () -> offloader.load(payload, context()));

        assertFalse(failure instanceof RetryablePayloadOffloadException);
    }

    @Test
    void loadRejectsMissingPayloadAsPermanentFailure() throws Exception {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory).build();
        var payload = offloader.offload("stored", context());
        Files.delete(Path.of(payload.reference()));

        var failure = assertThrows(PayloadOffloadException.class, () -> offloader.load(payload, context()));

        assertFalse(failure instanceof RetryablePayloadOffloadException);
    }

    @Test
    void writeEncodesTraversalSegmentsInsideConfiguredBasePath() throws Exception {
        var basePath = temporaryDirectory.resolve("base");
        var outsidePath = temporaryDirectory.resolve("outside");
        Files.createDirectory(basePath);
        var offloader = FileSystemPayloadOffloader.builder(basePath).build();
        var traversalContext = PayloadOffloadContext.forOperation(
                "arn:aws:lambda:us-east-1:123456789012:function:..:$LATEST/durable-execution/outside/invocation",
                OperationIdentifier.of("op-1", "step", OperationSubType.STEP),
                null,
                SerDesPayloadKind.RESULT,
                1);

        var payload = offloader.offload("stored", traversalContext);

        assertEquals(basePath, Path.of(payload.reference()).getParent());
        assertFalse(Files.exists(outsidePath));
    }

    @Test
    void writeEncodesFallbackTraversalInsideConfiguredBasePath() throws Exception {
        var basePath = temporaryDirectory.resolve("base");
        Files.createDirectory(basePath);
        var offloader = FileSystemPayloadOffloader.builder(basePath).build();
        var traversalContext = PayloadOffloadContext.forOperation(
                "..", OperationIdentifier.of("op-1", "step", OperationSubType.STEP), null, SerDesPayloadKind.RESULT, 1);

        var payload = offloader.offload("stored", traversalContext);

        assertEquals(basePath, Path.of(payload.reference()).getParent());
    }

    @Test
    void writeRejectsConfiguredBasePathThatIsASymbolicLink() throws Exception {
        var outsidePath = temporaryDirectory.resolve("outside");
        Files.createDirectories(outsidePath);
        var basePath = temporaryDirectory.resolve("base");
        Files.createSymbolicLink(basePath, outsidePath);
        var offloader = FileSystemPayloadOffloader.builder(basePath).build();

        var failure = assertThrows(PayloadOffloadException.class, () -> offloader.offload("stored", context()));

        assertFalse(failure instanceof RetryablePayloadOffloadException);
        try (var files = Files.list(outsidePath)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void writeDoesNotCreateMissingBasePathComponents() {
        var missingRoot = temporaryDirectory.resolve("missing");
        var offloader = FileSystemPayloadOffloader.builder(missingRoot.resolve("payloads"))
                .build();

        var failure = assertThrows(PayloadOffloadException.class, () -> offloader.offload("stored", context()));

        assertTrue(failure.getMessage().contains("base path and all ancestors must already exist"));
        assertFalse(failure instanceof RetryablePayloadOffloadException);
        assertFalse(Files.exists(missingRoot));
    }

    @Test
    void writeRejectsConfiguredBasePathThatIsNotDirectoryAsPermanentFailure() throws Exception {
        var basePath = temporaryDirectory.resolve("payloads");
        Files.writeString(basePath, "not a directory");
        var offloader = FileSystemPayloadOffloader.builder(basePath).build();

        var failure = assertThrows(PayloadOffloadException.class, () -> offloader.offload("stored", context()));

        assertFalse(failure instanceof RetryablePayloadOffloadException);
        assertTrue(failure.getMessage().contains("must be directories"));
    }

    @Test
    void readOnlyProviderFailureIsPermanent() {
        var providerFailure = new FileSystemException(temporaryDirectory.toString(), null, "Read-only file system");

        var failure = FileSystemPayloadOffloader.classifyIoFailure("store", context(), providerFailure);

        assertEquals(providerFailure, failure.getCause());
        assertFalse(failure instanceof RetryablePayloadOffloadException);
    }

    @Test
    void quotaCapacityAndUnknownFailuresArePermanent() {
        var quotaFailure = new FileSystemException(temporaryDirectory.toString(), null, "Disk quota exceeded");
        var capacityFailure = new FileSystemException(temporaryDirectory.toString(), null, "No space left on device");
        var unknownFailure = new java.io.IOException("provider configuration failure");

        assertFalse(
                FileSystemPayloadOffloader.classifyIoFailure("store", context(), quotaFailure)
                        instanceof RetryablePayloadOffloadException);
        assertFalse(
                FileSystemPayloadOffloader.classifyIoFailure("store", context(), capacityFailure)
                        instanceof RetryablePayloadOffloadException);
        assertFalse(
                FileSystemPayloadOffloader.classifyIoFailure("load", context(), unknownFailure)
                        instanceof RetryablePayloadOffloadException);
    }

    @Test
    void knownTransientProviderFailureRemainsRetryable() {
        var transientFailure = new FileSystemException(temporaryDirectory.toString(), null, "Stale file handle");

        assertTrue(
                FileSystemPayloadOffloader.classifyIoFailure("load", context(), transientFailure)
                        instanceof RetryablePayloadOffloadException);
    }

    @Test
    void differentEntitiesUseDifferentFiles() {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory).build();
        var first = offloader.offload("first", context());
        var secondContext = PayloadOffloadContext.forOperation(
                context().durableExecutionArn(),
                OperationIdentifier.of("op-2", "other", OperationSubType.STEP),
                null,
                SerDesPayloadKind.RESULT,
                1);
        var second = offloader.offload("second", secondContext);

        assertNotEquals(first.reference(), second.reference());
    }

    @Test
    void differentAttemptsUseDifferentFiles() {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory).build();
        var first = offloader.offload("first", context());
        var secondContext = PayloadOffloadContext.forOperation(
                context().durableExecutionArn(),
                OperationIdentifier.of("op/1", "step", OperationSubType.STEP),
                null,
                SerDesPayloadKind.RESULT,
                2);
        var second = offloader.offload("second", secondContext);

        assertNotEquals(first.reference(), second.reference());
        assertEquals("first", offloader.load(first, context()));
        assertEquals("second", offloader.load(second, secondContext));
    }

    @Test
    void tamperedPayloadFailsDigestValidation() throws Exception {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory).build();
        var payload = offloader.offload("stored", context());
        Files.writeString(Path.of(payload.reference()), "tampered");

        assertThrows(PayloadOffloadException.class, () -> offloader.load(payload, context()));
    }

    @Test
    void payloadCannotBeLoadedByDifferentEntity() {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory).build();
        var payload = offloader.offload("stored", context());
        var otherContext = PayloadOffloadContext.forOperation(
                context().durableExecutionArn(),
                OperationIdentifier.of("op-2", "other", OperationSubType.STEP),
                null,
                SerDesPayloadKind.RESULT,
                1);

        assertThrows(PayloadOffloadException.class, () -> offloader.load(payload, otherContext));
    }

    @Test
    void chainedInvokeResultCanLoadPayloadOwnedByTargetExecution() {
        var offloader = FileSystemPayloadOffloader.builder(temporaryDirectory).build();
        var targetContext = PayloadOffloadContext.forExecution(
                "arn:aws:lambda:us-east-1:123456789012:function:target:$LATEST/durable-execution/name/target-id",
                "target-id",
                "target",
                SerDesPayloadKind.OUTPUT);
        var payload = offloader.offload("stored", targetContext);
        var callerContext = PayloadOffloadContext.forOperation(
                context().durableExecutionArn(),
                OperationIdentifier.of("invoke", "target", OperationSubType.CHAINED_INVOKE),
                null,
                SerDesPayloadKind.RESULT,
                null);

        assertEquals("stored", offloader.load(payload, callerContext));
    }

    private static PayloadOffloadContext context() {
        return PayloadOffloadContext.forOperation(
                "arn:aws:lambda:us-east-1:123456789012:function:test-function:$LATEST/durable-execution/execution-name/invocation-id",
                OperationIdentifier.of("op/1", "step", OperationSubType.STEP),
                null,
                SerDesPayloadKind.RESULT,
                1);
    }
}
