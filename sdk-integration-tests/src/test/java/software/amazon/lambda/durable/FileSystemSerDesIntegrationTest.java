// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.serde.FileSystemSerDes;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class FileSystemSerDesIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void storesStepAndExecutionResultsAcrossReplay() throws Exception {
        var stepRuns = new AtomicInteger();
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var config = DurableConfig.builder().withSerDes(serDes).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var result = context.step("persist", String.class, stepContext -> {
                        stepRuns.incrementAndGet();
                        return input + "-stored";
                    });
                    context.wait("replay", Duration.ofSeconds(1));
                    return result;
                },
                config);

        var result = runner.runUntilComplete("value");

        assertEquals("value-stored", result.getResult(String.class));
        assertEquals("value-stored", result.getOperation("persist").getStepResult(String.class));
        assertEquals(1, stepRuns.get());
        try (var files = Files.walk(tempDir)) {
            assertTrue(files.filter(Files::isRegularFile).count() >= 2);
        }
    }

    @Test
    void operationConfigControlsWhereFilesystemStorageIsUsed() throws Exception {
        var fileSystemSerDes = FileSystemSerDes.builder(tempDir).build();
        var config = DurableConfig.builder().withSerDes(new JacksonSerDes()).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "filesystem-step",
                        String.class,
                        stepContext -> input + "-stored",
                        StepConfig.builder().serDes(fileSystemSerDes).build()),
                config);

        var result = runner.runUntilComplete("value");

        assertEquals("value-stored", result.getResult(String.class));
        try (var files = Files.walk(tempDir)) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }
    }
}
