// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LegacySubclassMigrationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"Invocation,false", "Invocation,true", "Execution,false", "Execution,true"})
    void requiresFactoryMigrationForOldCompiledSubclasses(String view, boolean ownMethod) throws Exception {
        var baseline = Files.createDirectories(directory.resolve("baseline"));
        var classes = Files.createDirectories(directory.resolve("classes"));
        var baseName = view + "OtelPlugin";
        var oldBase = directory.resolve(baseName + ".java");
        Files.writeString(oldBase, "package software.amazon.lambda.durable.otel; public class " + baseName + " {}");
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(
                0, compiler.run(null, null, null, "--release", "17", "-d", baseline.toString(), oldBase.toString()));
        var source = directory.resolve("LegacySubclass.java");
        var method = "public AutoCloseable openHandlerScope() { opened++; return () -> closed++; }";
        Files.writeString(source, """
                import software.amazon.lambda.durable.otel.%s;
                interface ApplicationScope {
                    default AutoCloseable openHandlerScope() {
                        LegacySubclass.opened++;
                        return () -> LegacySubclass.closed++;
                    }
                }
                public class LegacySubclass extends %s implements ApplicationScope {
                    public static int opened, closed;
                    %s
                    public void originalCall() throws Exception { try (var scope = openHandlerScope()) {} }
                }
                """.formatted(baseName, baseName, ownMethod ? method : ""));
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
            assertThrows(
                    IncompatibleClassChangeError.class,
                    () -> loader.loadClass("LegacySubclass"),
                    "3.x view implementations are final; old subclasses must migrate to factories");
        }
        assertNotEquals(
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
