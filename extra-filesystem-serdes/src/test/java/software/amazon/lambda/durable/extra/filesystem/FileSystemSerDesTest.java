// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extra.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.serde.SerDesPayloadKind;
import software.amazon.lambda.durable.serde.SerDesRunner;

class FileSystemSerDesTest {
    private static final String ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:orders:1/durable-execution/execution-1/invocation-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path basePath;

    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void alwaysModeWritesDelegatePayloadAndReplaysIt() throws Exception {
        var serDes = FileSystemSerDes.builder(basePath).build();
        var runner = new SerDesRunner(executor);

        var envelope = runner.serialize(serDes, Map.of("id", 42), context());
        var file = Path.of(MAPPER.readTree(envelope).get("file").textValue());

        assertTrue(file.startsWith(basePath.resolve("orders/execution-1/invocation-1")));
        assertEquals("{\"id\":42}", Files.readString(file));
        assertEquals(
                Map.of("id", 42),
                runner.deserialize(serDes, envelope, new TypeToken<Map<String, Integer>>() {}, context()));
    }

    @Test
    void overflowModeKeepsSmallPayloadInlineAndWritesLargePayload() throws Exception {
        var serDes = FileSystemSerDes.builder(basePath)
                .storageMode(FileSystemStorageMode.OVERFLOW)
                .build();
        var runner = new SerDesRunner(executor);

        var inline = runner.serialize(serDes, "small", context());
        assertTrue(MAPPER.readTree(inline).has("data"));

        var overflow = runner.serialize(serDes, "x".repeat(256 * 1024), context());
        assertTrue(MAPPER.readTree(overflow).has("file"));
    }

    @Test
    void hashEncodingUsesFixedLengthSegments() throws Exception {
        var serDes = FileSystemSerDes.builder(basePath)
                .pathEncoding(FileSystemPathEncoding.HASH)
                .build();

        var envelope = new SerDesRunner(executor).serialize(serDes, "value", context());
        var file = Path.of(MAPPER.readTree(envelope).get("file").textValue());

        assertEquals(64, file.getParent().getFileName().toString().length());
        assertEquals(69, file.getFileName().toString().length());
        assertFalse(file.toString().contains("operation"));
    }

    @Test
    void includesPreviewWithoutChangingStoredPayload() throws Exception {
        var serDes = FileSystemSerDes.builder(basePath)
                .previewGenerator(value -> Map.of("summary", "order"))
                .build();

        var envelope = new SerDesRunner(executor).serialize(serDes, Map.of("secret", "value"), context());
        var json = MAPPER.readTree(envelope);

        assertEquals("order", json.get("preview").get("summary").textValue());
        assertEquals(
                "{\"secret\":\"value\"}",
                Files.readString(Path.of(json.get("file").textValue())));
    }

    @Test
    void rejectsCallsWithoutSdkContextAndMalformedEnvelopes() {
        var serDes = FileSystemSerDes.builder(basePath).build();
        assertThrows(SerDesException.class, () -> serDes.serialize("value"));

        var runner = new SerDesRunner(executor);
        assertThrows(
                SerDesException.class, () -> runner.deserialize(serDes, "{}", TypeToken.get(String.class), context()));
        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(
                        serDes, "{\"file\":\"/outside/payload.json\"}", TypeToken.get(String.class), context()));
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

        assertThrows(SerDesException.class, () -> new SerDesRunner(executor).serialize(serDes, "value", unsafeContext));
    }

    private static SerDesContext context() {
        return SerDesContext.forOperation(
                ARN, "1", "step", null, OperationType.STEP, OperationSubType.STEP, SerDesPayloadKind.RESULT, 1);
    }
}
