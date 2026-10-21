// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

class ConfigurationCopyDiscoveryTest {
    @Test
    @Timeout(20)
    void migrationCopyRetainsOneEnvironmentSelectedFactory(@TempDir Path directory) throws Exception {
        var output = directory.resolve("environment-copy.log");
        var process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                        EnvironmentCopy.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        process.environment().put("DURABLE_EXECUTION_PLUGINS", "otel-invocation");
        var child = process.start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "environment copy fixture timed out");
            assertEquals(0, child.exitValue(), () -> read(output));
            assertTrue(read(output).contains("PASS selected=1 copied=1 handler=SUCCEEDED"));
            System.out.println(read(output));
        } finally {
            child.destroyForcibly();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    public static class EnvironmentCopy {
        public static void main(String[] args) {
            assertEquals("otel-invocation", System.getenv("DURABLE_EXECUTION_PLUGINS"));
            var config = DurableConfig.builder().build();
            assertEquals(1, config.getPluginFactories().size());
            var duplicate = assertThrows(
                    IllegalArgumentException.class,
                    () -> DurableConfig.builder()
                            .withPlugins(config.getPluginFactories().toArray(new DurableExecutionPluginFactory[0]))
                            .build());
            assertTrue(duplicate.getMessage().contains("durable-otel-view"));
            var testClient = new LocalMemoryExecutionClient();
            var derived =
                    config.toBuilder().withDurableExecutionClient(testClient).build();
            assertSame(testClient, derived.getDurableExecutionClient());
            assertEquals(1, derived.getPluginFactories().size());
            assertSame(
                    config.getPluginFactories().get(0),
                    derived.getPluginFactories().get(0));
            var result = LocalDurableTestRunner.create(String.class, (input, context) -> input, derived)
                    .run("ok");
            assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
            assertEquals("ok", result.getResult(String.class));
            System.out.println("PASS selected=1 copied=1 handler=SUCCEEDED");
        }
    }
}
