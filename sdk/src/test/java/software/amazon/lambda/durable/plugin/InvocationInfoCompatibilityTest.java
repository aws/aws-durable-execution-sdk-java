// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;

class InvocationInfoCompatibilityTest {
    // The released public constructor/accessor/record shape, compiled separately from the current SDK.
    private static final String LEGACY_API = """
            package software.amazon.lambda.durable.plugin;
            import java.time.Instant;
            import java.util.Map;
            public record InvocationInfo(String requestId, String durableExecutionArn, boolean isFirstInvocation,
                    Instant executionStartTime, Object executionInput, Map<String,OperationChangeItemInfo> operations,
                    Map<String,OperationChangeItemInfo> updatedOperations) {
                public InvocationInfo(String request, String arn, boolean first, Instant start) {
                    this(request,arn,first,start,null,Map.of(),Map.of());
                }
                public InvocationInfo(String request, String arn, boolean first, Instant start, Object input) {
                    this(request,arn,first,start,input,Map.of(),Map.of());
                }
                public InvocationInfo(String request, String arn, boolean first, Instant start,
                        Map<String,OperationChangeItemInfo> operations, Map<String,OperationChangeItemInfo> updated) {
                    this(request,arn,first,start,null,operations,updated);
                }
            }
            """;
    private static final String CONSTRUCTOR_CALLER = """
            import software.amazon.lambda.durable.plugin.InvocationInfo;
            import java.time.Instant;
            import java.util.Map;
            import java.util.List;
            public class LegacyConstructorProbe {
                public static List<InvocationInfo> construct() {
                    var four = new InvocationInfo("four","arn",true,Instant.EPOCH);
                    var five = new InvocationInfo("five","arn",true,Instant.EPOCH,"input");
                    var six = new InvocationInfo("six","arn",true,Instant.EPOCH,Map.of(),Map.of());
                    var seven = new InvocationInfo("seven","arn",true,Instant.EPOCH,"input",Map.of(),Map.of());
                    Record record = seven;
                    if (!"arn".equals(seven.durableExecutionArn()) || !seven.isFirstInvocation()
                            || !Instant.EPOCH.equals(seven.executionStartTime()) || !"input".equals(seven.executionInput())
                            || !six.operations().isEmpty() || !seven.updatedOperations().isEmpty()
                            || !record.getClass().isRecord()) throw new AssertionError();
                    return List.of(four,five,six,seven);
                }
            }
            """;
    private static final String LEGACY_PATTERN = """
            import software.amazon.lambda.durable.plugin.InvocationInfo;
            public class RecordPatternProbe {
                public static String read(Object event) {
                    if (event instanceof InvocationInfo(var request, var arn, var first, var start,
                            var input, var operations, var updated)) return request;
                    return null;
                }
            }
            """;

    @Test
    void oldConstructorCallsRetainSourceAndBinaryCompatibility(@TempDir Path directory) throws Exception {
        var oldApi = legacyApi(directory);
        var oldCaller = directory.resolve("old-caller");
        assertCompiled(compile(
                oldCaller,
                "LegacyConstructorProbe",
                CONSTRUCTOR_CALLER,
                oldApi + File.pathSeparator + classpath(),
                "17"));
        var newCaller = directory.resolve("new-caller");
        assertCompiled(compile(newCaller, "LegacyConstructorProbe", CONSTRUCTOR_CALLER, classpath(), "17"));
        for (var caller : List.of(oldCaller, newCaller)) {
            try (var loader = loader(caller)) {
                var result = (List<?>) loader.loadClass("LegacyConstructorProbe")
                        .getMethod("construct")
                        .invoke(null);
                assertEquals(4, result.size());
                for (var value : result) {
                    var info = assertInstanceOf(InvocationInfo.class, value);
                    assertNull(
                            info.xRayTraceId(), "All retained constructors default the optional field to unavailable");
                }
            }
        }
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21)
    void eightComponentPatternReadsTheActualHeaderField(@TempDir Path directory) throws Exception {
        var source = LEGACY_PATTERN.replace("var updated)) return request", "var updated, var header)) return header");
        assertCompiled(compile(directory, "RecordPatternProbe", source, classpath(), "21"));
        try (var loader = loader(directory)) {
            assertEquals(
                    "runtime-header",
                    loader.loadClass("RecordPatternProbe")
                            .getMethod("read", Object.class)
                            .invoke(null, invocation()));
        }
        var components = InvocationInfo.class.getRecordComponents();
        assertEquals(8, components.length);
        assertEquals("xRayTraceId", components[7].getName());
        assertEquals(String.class, components[7].getType());
        assertFalse(invocation().toString().contains("runtime-header"));
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21)
    void sevenComponentPatternHasAnExplicitSourceBoundaryButOldBytecodeStillRuns(@TempDir Path directory)
            throws Exception {
        var oldApi = legacyApi(directory);
        var oldCaller = directory.resolve("old-pattern");
        assertCompiled(compile(
                oldCaller, "RecordPatternProbe", LEGACY_PATTERN, oldApi + File.pathSeparator + classpath(), "21"));
        var recompiled =
                compile(directory.resolve("new-pattern"), "RecordPatternProbe", LEGACY_PATTERN, classpath(), "21");
        assertNotEquals(0, recompiled.exitCode(), "An eighth component cannot preserve seven-component pattern source");
        assertTrue(recompiled.errors().contains("incorrect number of nested patterns"), recompiled.errors());
        try (var loader = loader(oldCaller)) {
            assertEquals(
                    "request",
                    loader.loadClass("RecordPatternProbe")
                            .getMethod("read", Object.class)
                            .invoke(null, invocation()));
        }
    }

    private static InvocationInfo invocation() {
        return new InvocationInfo("request", "arn", true, Instant.EPOCH, "input", Map.of(), Map.of(), "runtime-header");
    }

    private static Path legacyApi(Path directory) throws Exception {
        var output = directory.resolve("old-api");
        assertCompiled(compile(output, "InvocationInfo", LEGACY_API, classpath(), "17"));
        return output;
    }

    private static String classpath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    private URLClassLoader loader(Path directory) throws Exception {
        // The consumer is loaded from disk; InvocationInfo resolves to the current SDK, never the old fixture.
        return new URLClassLoader(
                new URL[] {directory.toUri().toURL()}, getClass().getClassLoader());
    }

    private static Compilation compile(Path output, String name, String text, String classpath, String release)
            throws Exception {
        var source = output.resolve("src").resolve(name + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, text);
        var errors = new ByteArrayOutputStream();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Compatibility tests require a JDK");
        var status = compiler.run(
                null,
                null,
                errors,
                "--release",
                release,
                "-classpath",
                classpath,
                "-d",
                output.toString(),
                source.toString());
        return new Compilation(status, errors.toString(StandardCharsets.UTF_8));
    }

    private static void assertCompiled(Compilation result) {
        assertEquals(0, result.exitCode(), result.errors());
    }

    private record Compilation(int exitCode, String errors) {}
}
