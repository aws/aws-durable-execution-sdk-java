// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.general;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.ExampleTemplate;
import software.amazon.lambda.durable.offload.filesystem.FileSystemPayloadOffloader;
import software.amazon.lambda.durable.offload.filesystem.PreviewConfig;
import software.amazon.lambda.durable.offload.filesystem.PreviewField;
import software.amazon.lambda.durable.offload.filesystem.PreviewMode;

/** E2E fixture that offloads durable payloads to an EFS mount and reads them after reinvocation. */
@ExampleTemplate(fileSystem = true)
public class FileSystemPayloadOffloaderExample
        extends DurableHandler<FileSystemPayloadOffloaderExample.Input, FileSystemPayloadOffloaderExample.Output> {
    private static final String FILE_SYSTEM_PATH_ENV = "FILESYSTEM_PAYLOAD_PATH";

    @Override
    protected DurableConfig createConfiguration() {
        var path = System.getenv(FILE_SYSTEM_PATH_ENV);
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(FILE_SYSTEM_PATH_ENV + " must identify the mounted durable filesystem");
        }
        var preview = PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
                .include(PreviewField.anywhere("id"), PreviewField.anywhere("length"))
                .mask(PreviewField.anywhere("value"))
                .build();
        var offloader = FileSystemPayloadOffloader.builder(Path.of(path))
                .previewConfig(preview)
                .build();
        return DurableConfig.builder().withPayloadOffloader(offloader).build();
    }

    @Override
    public Output handleRequest(Input input, DurableContext context) {
        var stored =
                context.step("store-payload", Payload.class, stepContext -> new Payload(input.id(), input.value()));
        context.wait("force-filesystem-replay", Duration.ofSeconds(1));
        return context.step(
                "verify-payload",
                Output.class,
                stepContext -> new Output(stored.id(), stored.value().length(), sha256(stored.value())));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record Input(String id, String value) {}

    public record Payload(String id, String value) {}

    public record Output(String id, int length, String checksum) {}
}
