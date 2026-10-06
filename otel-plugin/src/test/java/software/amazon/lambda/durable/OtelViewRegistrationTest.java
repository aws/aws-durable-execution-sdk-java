// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.otel.ExecutionOtelPlugin;
import software.amazon.lambda.durable.otel.ExecutionOtelPluginProvider;
import software.amazon.lambda.durable.otel.InvocationOtelPlugin;
import software.amazon.lambda.durable.otel.InvocationOtelPluginProvider;
import software.amazon.lambda.durable.otel.OtelPluginConfig;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.ExclusivePluginGroup;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class OtelViewRegistrationTest {
    @Test
    void existingSubclassGroupMethodsRemainCompatible() {
        // Downstream subclasses could already declare this method before group metadata joined the plugin API.
        var invocation = new InvocationOtelPlugin() {
            public String getExclusiveGroup() {
                return "legacy application group";
            }
        };
        var execution = new ExecutionOtelPlugin() {
            public String getExclusiveGroup() {
                return null;
            }
        };
        assertEquals("legacy application group", invocation.getExclusiveGroup());
        assertNull(execution.getExclusiveGroup());
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> DurableConfig.builder().withPlugins(invocation, execution).build());
        assertTrue(error.getMessage().contains(invocation.getClass().getName()));
        assertTrue(error.getMessage().contains(execution.getClass().getName()));
        assertDoesNotThrow(() -> DurableConfig.builder().withPlugins(invocation).build());
    }

    @ParameterizedTest
    @CsvSource({"true,false", "true,true", "false,false", "false,true"})
    void reannotatedSubclassesRetainViewExclusivity(boolean executionSubclass, boolean reversed) {
        DurableExecutionPlugin custom =
                executionSubclass ? new ReannotatedExecutionPlugin() : new ReannotatedInvocationPlugin();
        DurableExecutionPlugin opposite = executionSubclass ? new InvocationOtelPlugin() : new ExecutionOtelPlugin();
        var first = reversed ? opposite : custom;
        var second = reversed ? custom : opposite;
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> DurableConfig.builder().withPlugins(first, second).build());
        assertTrue(error.getMessage().contains("durable-otel-view"));
    }

    @ExclusivePluginGroup("application-instrumentation")
    private static class ReannotatedInvocationPlugin extends InvocationOtelPlugin {}

    @ExclusivePluginGroup("application-instrumentation")
    private static class ReannotatedExecutionPlugin extends ExecutionOtelPlugin {}

    @ParameterizedTest
    @CsvSource({"explicit,false", "explicit,true", "dynamic,false", "dynamic,true", "mixed,false", "mixed,true"})
    void rejectsBothViewsBeforeEmissionAndLeavesContextUntouched(String path, boolean reversed) {
        var exporter = InMemorySpanExporter.create();
        var first = plugin(reversed, exporter);
        var second = plugin(!reversed, exporter);
        var providers = List.of(new ExecutionOtelPluginProvider(), new InvocationOtelPluginProvider());
        var names = reversed ? "otel-execution,otel-invocation" : "otel-invocation,otel-execution";
        var plugins =
                switch (path) {
                    case "dynamic" -> DynamicPluginLoader.loadConfiguredPlugins(names, providers, List.of());
                    case "mixed" ->
                        DynamicPluginLoader.loadConfiguredPlugins(
                                reversed ? "otel-execution" : "otel-invocation", providers, List.of(second));
                    default -> List.of(first, second);
                };
        var ambient = Span.wrap(SpanContext.create(
                "12345678901234567890123456789012",
                "1234567890123456",
                TraceFlags.getSampled(),
                TraceState.getDefault()));
        try (var ignored = ambient.makeCurrent()) {
            var context = Context.current();
            MDC.put("trace_id", "existing");
            try {
                var error = assertThrows(
                        IllegalArgumentException.class,
                        () -> DurableConfig.builder()
                                .withPlugins(plugins.toArray(DurableExecutionPlugin[]::new))
                                .build());
                assertTrue(error.getMessage().contains("InvocationOtelPlugin"));
                assertTrue(error.getMessage().contains("ExecutionOtelPlugin"));
                assertTrue(error.getMessage().contains("only one"));
                assertTrue(exporter.getFinishedSpanItems().isEmpty());
                assertSame(context, Context.current());
                assertEquals("existing", MDC.get("trace_id"));
            } finally {
                MDC.remove("trace_id");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void singleViewWithUnrelatedPluginPreservesResumeAndOutcome(boolean executionView, boolean success) {
        var exporter = InMemorySpanExporter.create();
        var view = plugin(executionView, exporter);
        var config = DurableConfig.builder()
                .withPlugins(view, new DurableExecutionPlugin() {})
                .build();
        var effects = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    ctx.step("once", Integer.class, stepCtx -> effects.incrementAndGet());
                    ctx.wait("pause", Duration.ofSeconds(10));
                    if (!success) {
                        throw new IllegalArgumentException("expected failure");
                    }
                    return input;
                },
                config);
        var original = Context.current();
        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        runner.advanceTime();
        assertEquals(
                success ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                runner.runUntilComplete("input").getStatus());
        assertSame(original, Context.current());
        assertEquals(1, effects.get());
        var spans = exporter.getFinishedSpanItems();
        assertEquals(
                1, spans.stream().filter(s -> s.getName().equals("Workflow")).count());
        assertEquals(
                2, spans.stream().filter(s -> s.getName().equals("Invocation")).count());
    }

    @ParameterizedTest
    @CsvSource({"otel-invocation,false", "otel-execution,false", "otel-invocation,true", "otel-execution,true"})
    void environmentSelectedSingleViewCanBeCopiedIntoLocalRunner(
            String provider, boolean releasedTestingSdk, @TempDir Path directory) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var testingLocation =
                releasedTestingSdk ? Path.of(System.getProperty("releasedTestingSdkJar")) : testingSdkLocation();
        assertTrue(Files.exists(testingLocation), "Testing SDK artifact must exist");
        classpath = testingLocation + File.pathSeparator + classpath;
        var output = directory.resolve("child.log");
        var builder = new ProcessBuilder(
                        java, "-cp", classpath, EnvironmentRunnerCheck.class.getName(), testingLocation.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().put("DURABLE_EXECUTION_PLUGINS", provider);
        var child = builder.start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Child JVM did not finish");
            assertEquals(0, child.exitValue(), () -> read(output));
        } finally {
            child.destroyForcibly();
        }
    }

    private static Path testingSdkLocation() throws URISyntaxException {
        return Path.of(LocalDurableTestRunner.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static class EnvironmentRunnerCheck {
        public static void main(String[] args) throws Exception {
            // Ensure the old-testing cases execute the released bytecode, not the reactor's updated runner.
            assertEquals(Path.of(args[0]).toRealPath(), testingSdkLocation().toRealPath());
            var config = DurableConfig.builder()
                    .withDeserializeAfterSerialization(false)
                    .build();
            assertEquals(1, config.getPluginRunner().getPlugins().size());
            var copy = config.toBuilder().build();
            assertEquals(
                    config.getPluginRunner().getPlugins(),
                    copy.getPluginRunner().getPlugins());
            assertFalse(copy.shouldDeserializeAfterSerialization());
            var effects = new AtomicInteger();
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, ctx) -> {
                        ctx.step("once", Integer.class, step -> effects.incrementAndGet());
                        ctx.wait("pause", Duration.ofSeconds(1));
                        return input;
                    },
                    config);
            assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
            runner.advanceTime();
            var result = runner.runUntilComplete("input");
            assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
            assertEquals("input", result.getResult(String.class));
            assertEquals(1, effects.get());
        }
    }

    private static DurableExecutionPlugin plugin(boolean executionView, InMemorySpanExporter exporter) {
        var builder = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var config = OtelPluginConfig.builder()
                .contextExtractor(() -> null)
                .enableMdc(false)
                .build();
        return executionView ? new ExecutionOtelPlugin(builder, config) : new InvocationOtelPlugin(builder, config);
    }
}
