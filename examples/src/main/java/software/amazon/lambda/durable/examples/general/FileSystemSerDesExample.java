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
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.serde.FileSystemPathEncoding;
import software.amazon.lambda.durable.serde.FileSystemSerDes;
import software.amazon.lambda.durable.serde.FileSystemSerDesMode;
import software.amazon.lambda.durable.serde.PreviewConfig;
import software.amazon.lambda.durable.serde.PreviewField;
import software.amazon.lambda.durable.serde.PreviewMode;
import software.amazon.lambda.durable.serde.RetrySerDes;

/** Stores durable payloads on a shared filesystem and verifies them after replay. */
@ExampleTemplate(fileSystem = true)
public class FileSystemSerDesExample
        extends DurableHandler<FileSystemSerDesExample.Input, FileSystemSerDesExample.Output> {
    static final String FILE_SYSTEM_PATH_PROPERTY = "filesystem.serdes.path";
    private static final String FILE_SYSTEM_PATH_ENV = "FILESYSTEM_SERDES_PATH";

    @Override
    protected DurableConfig createConfiguration() {
        var path = System.getProperty(FILE_SYSTEM_PATH_PROPERTY);
        if (path == null || path.isBlank()) {
            path = System.getenv(FILE_SYSTEM_PATH_ENV);
        }
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(FILE_SYSTEM_PATH_ENV + " must identify the mounted durable filesystem");
        }

        var fileSystemSerDes = FileSystemSerDes.builder(Path.of(path))
                .storageMode(FileSystemSerDesMode.ALWAYS)
                .pathEncoding(FileSystemPathEncoding.HASH)
                .previewConfig(PreviewConfig.builder(PreviewMode.EXCLUDE_ALL)
                        .include(
                                PreviewField.anywhere("id"),
                                PreviewField.anywhere("length"),
                                PreviewField.anywhere("checksum"))
                        .mask(PreviewField.anywhere("email"))
                        .build())
                .build();
        var retryingSerDes = new RetrySerDes(
                fileSystemSerDes,
                RetryStrategies.exponentialBackoff(
                        4, Duration.ofSeconds(1), Duration.ofSeconds(10), 2.0, JitterStrategy.FULL));
        return DurableConfig.builder().withSerDes(retryingSerDes).build();
    }

    @Override
    public Output handleRequest(Input input, DurableContext context) {
        var stored = context.step(
                "store-payload",
                Payload.class,
                stepContext -> new Payload(
                        input.id(), input.email(), input.value(), input.value().length()));
        context.wait("force-filesystem-replay", Duration.ofSeconds(1));
        return context.step(
                "verify-payload",
                Output.class,
                stepContext -> new Output(stored.id(), stored.length(), sha256(stored.value())));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record Input(String id, String email, String value) {}

    public record Payload(String id, String email, String value, int length) {}

    public record Output(String id, int length, String checksum) {}
}
