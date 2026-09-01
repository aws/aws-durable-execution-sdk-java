// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde.filesystem;

import java.nio.file.Files;
import java.util.Map;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.serde.SerDesPayloadKind;

/** Verifies oversized custom previews fail cleanly in a deliberately constrained JVM. */
public final class FileSystemEnvelopeHeapProbe {
    private static final int OVERSIZED_PREVIEW_LENGTH = 12 * 1024 * 1024;
    private static final String ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:orders:1/durable-execution/execution-1/invocation-1";

    private FileSystemEnvelopeHeapProbe() {}

    public static void main(String[] args) throws Exception {
        var basePath = Files.createTempDirectory("filesystem-envelope-heap-probe-");
        try {
            var oversizedValue = "x".repeat(OVERSIZED_PREVIEW_LENGTH);
            var stage = FileSystemSerDesStage.builder(basePath)
                    .checkpointEnvelopeLimitBytes(2048)
                    .previewGenerator((value, context) -> Map.of("summary", oversizedValue))
                    .build();

            try {
                stage.serialize("value", context());
                throw new AssertionError("Expected the oversized envelope to be rejected");
            } catch (SerDesException expected) {
                if (!expected.getMessage().contains("checkpoint payload limit")) {
                    throw expected;
                }
            }

            try (var files = Files.list(basePath)) {
                if (files.findAny().isPresent()) {
                    throw new AssertionError("Oversized preview published a payload file");
                }
            }
        } finally {
            Files.deleteIfExists(basePath);
        }
    }

    private static SerDesContext context() {
        return SerDesContext.forOperation(
                ARN, "1", "step", null, OperationType.STEP, OperationSubType.STEP, SerDesPayloadKind.RESULT, 1);
    }
}
