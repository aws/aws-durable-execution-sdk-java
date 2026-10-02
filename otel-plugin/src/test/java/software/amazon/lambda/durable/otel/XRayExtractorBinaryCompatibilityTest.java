// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationRuntimeContext;

class XRayExtractorBinaryCompatibilityTest {
    @Test
    void subclassCompiledAgainstLegacyApiRetainsOverride(@TempDir Path directory) throws Exception {
        var baseline = directory.resolve("baseline");
        var consumer = directory.resolve("consumer");
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        compile(baseline, "XRayContextExtractor", """
                package software.amazon.lambda.durable.otel;
                public class XRayContextExtractor {
                    public ExtractedContext extract() { return null; }
                }
                """, classpath);
        compile(consumer, "LegacyExtractor", """
                import software.amazon.lambda.durable.otel.*;
                public class LegacyExtractor extends XRayContextExtractor {
                    public int calls;
                    @Override public ExtractedContext extract() {
                        calls++;
                        return new ExtractedContext("6955b900aaaaaaaaaaaaaaaaaaaaaaaa", null,
                                ExtractedContext.Sampling.NOT_SAMPLED);
                    }
                }
                """, baseline + File.pathSeparator + classpath);
        verifyConsumer(consumer);
    }

    private void verifyConsumer(Path consumer) throws Exception {
        // Only the consumer is loaded from disk: its superclass resolves to the current SDK in the parent loader.
        try (var loader = new URLClassLoader(
                new URL[] {consumer.toUri().toURL()}, getClass().getClassLoader())) {
            var type = loader.loadClass("LegacyExtractor");
            var extractor = (XRayContextExtractor) type.getConstructor().newInstance();
            var info = new InvocationInfo("request", "arn", true, Instant.EPOCH);
            var runtime = new InvocationRuntimeContext(
                    "Root=1-6955b900-123456789012345678901234;Parent=1234567890123456;Sampled=1");
            var extracted = extractor.extract(info, runtime);
            assertEquals("6955b900aaaaaaaaaaaaaaaaaaaaaaaa", extracted.traceId());
            assertEquals(ExtractedContext.Sampling.NOT_SAMPLED, extracted.sampling());
            assertEquals(1, type.getField("calls").get(extractor));
        }
    }

    private static void compile(Path directory, String name, String source, String classpath) throws Exception {
        Files.createDirectories(directory);
        var file = directory.resolve("src").resolve(name + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        var errors = new ByteArrayOutputStream();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Binary compatibility test requires a JDK");
        assertEquals(
                0,
                compiler.run(
                        null,
                        null,
                        errors,
                        "--release",
                        "17",
                        "-classpath",
                        classpath,
                        "-d",
                        directory.toString(),
                        file.toString()),
                errors.toString(StandardCharsets.UTF_8));
    }
}
