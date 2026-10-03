// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;

class InvocationInfoCompatibilityTest {
    @Test
    @EnabledForJreRange(min = JRE.JAVA_21)
    void existingJava21RecordPatternCompilesAndRuns(@TempDir Path directory) throws Exception {
        var source = directory.resolve("RecordPatternProbe.java");
        Files.writeString(source, """
                import software.amazon.lambda.durable.plugin.InvocationInfo;
                public class RecordPatternProbe {
                    public static String requestId(Object event) {
                        if (event instanceof InvocationInfo(var request, var arn, var first, var start,
                                var input, var operations, var updated)) return request;
                        return null;
                    }
                }
                """);
        var errors = new ByteArrayOutputStream();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Source compatibility requires a JDK");
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        assertEquals(
                0,
                compiler.run(
                        null,
                        null,
                        errors,
                        "--release",
                        "21",
                        "-classpath",
                        classpath,
                        "-d",
                        directory.toString(),
                        source.toString()),
                errors.toString(StandardCharsets.UTF_8));
        try (var loader = new URLClassLoader(
                new URL[] {directory.toUri().toURL()}, getClass().getClassLoader())) {
            var probe = loader.loadClass("RecordPatternProbe");
            assertEquals(
                    "request",
                    probe.getMethod("requestId", Object.class)
                            .invoke(null, new InvocationInfo("request", "arn", true, Instant.EPOCH)));
        }
    }
}
