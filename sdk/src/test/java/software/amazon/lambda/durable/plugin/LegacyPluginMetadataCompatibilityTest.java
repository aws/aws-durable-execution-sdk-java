// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyPluginMetadataCompatibilityTest {
    @TempDir
    Path directory;

    @Test
    void precompiledUnrelatedDefaultMethodStillLinksAndConfigures() throws Exception {
        var baseline = directory.resolve("baseline");
        var classes = directory.resolve("classes");
        Files.createDirectories(baseline);
        Files.createDirectories(classes);
        var oldApi = directory.resolve("DurableExecutionPlugin.java");
        Files.writeString(oldApi, """
                package software.amazon.lambda.durable.plugin;
                public interface DurableExecutionPlugin {}
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(
                0, compiler.run(null, null, null, "--release", "17", "-d", baseline.toString(), oldApi.toString()));
        var source = directory.resolve("LegacyPlugin.java");
        Files.writeString(source, """
                import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
                interface ExistingMetadata {
                    default String getExclusiveGroup() { return "legacy application value"; }
                }
                public class LegacyPlugin implements DurableExecutionPlugin, ExistingMetadata {
                    public String originalCall() { return getExclusiveGroup(); }
                }
                """);
        assertEquals(
                0,
                compiler.run(
                        null,
                        null,
                        null,
                        "--release",
                        "17",
                        "-cp",
                        baseline.toString(),
                        "-d",
                        classes.toString(),
                        source.toString()));
        try (var loader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            var type = loader.loadClass("LegacyPlugin");
            var plugin = (DurableExecutionPlugin) type.getConstructor().newInstance();
            assertDoesNotThrow(() -> new PluginRunner(List.of(plugin)));
            assertEquals(
                    "legacy application value", type.getMethod("originalCall").invoke(plugin));
        }
        // Source compatibility matters too: the same downstream source compiles against this core.
        assertEquals(
                0,
                compiler.run(
                        null,
                        null,
                        null,
                        "--release",
                        "17",
                        "-cp",
                        System.getProperty("java.class.path"),
                        "-d",
                        classes.toString(),
                        source.toString()));
    }
}
