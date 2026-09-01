// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.general;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class FileSystemSerDesExampleTest {
    @TempDir
    Path tempDir;

    @Test
    @ResourceLock(FileSystemSerDesExample.FILE_SYSTEM_PATH_PROPERTY)
    void storesAndReplaysPayloadsFromFilesystem() throws Exception {
        System.setProperty(FileSystemSerDesExample.FILE_SYSTEM_PATH_PROPERTY, tempDir.toString());
        try {
            var handler = new FileSystemSerDesExample();
            var runner = LocalDurableTestRunner.create(FileSystemSerDesExample.Input.class, handler);
            var value = "filesystem-value-".repeat(1024);

            var result =
                    runner.runUntilComplete(new FileSystemSerDesExample.Input("payload-1", "user@example.com", value));
            var output = result.getResult(FileSystemSerDesExample.Output.class);

            assertEquals("payload-1", output.id());
            assertEquals(value.length(), output.length());
            assertEquals(
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8))),
                    output.checksum());
            try (var files = Files.walk(tempDir)) {
                assertTrue(files.filter(Files::isRegularFile).count() >= 3);
            }
        } finally {
            System.clearProperty(FileSystemSerDesExample.FILE_SYSTEM_PATH_PROPERTY);
        }
    }
}
