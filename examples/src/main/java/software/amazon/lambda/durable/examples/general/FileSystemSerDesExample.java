// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.general;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.ExampleTemplate;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.serde.filesystem.FileSystemSerDesStage;

/** E2E fixture that offloads durable payloads to an EFS mount and reads them after reinvocation. */
@ExampleTemplate(fileSystem = true)
public class FileSystemSerDesExample
        extends DurableHandler<FileSystemSerDesExample.Input, FileSystemSerDesExample.Output> {
    private static final String FILE_SYSTEM_PATH_ENV = "FILESYSTEM_SERDES_PATH";

    @Override
    protected DurableConfig createConfiguration() {
        var path = System.getenv(FILE_SYSTEM_PATH_ENV);
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(FILE_SYSTEM_PATH_ENV + " must identify the mounted durable filesystem");
        }
        var fileSystemStage = FileSystemSerDesStage.builder(Path.of(path))
                .previewGenerator(FileSystemSerDesExample::preview)
                .build();
        return DurableConfig.builder()
                .withSerDes(new JacksonSerDes().then(fileSystemStage))
                .build();
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

    private static Map<String, Object> preview(String value, SerDesContext context) {
        var preview = new LinkedHashMap<String, Object>();
        preview.put("payloadKind", context.payloadKind().name());
        preview.put("operationName", context.operationName());
        if (context.originalValue() instanceof Payload payload) {
            preview.put("id", payload.id());
            preview.put("length", payload.value().length());
        } else if (context.originalValue() instanceof Output output) {
            preview.put("id", output.id());
            preview.put("length", output.length());
            preview.put("checksum", output.checksum());
        }
        return preview;
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
