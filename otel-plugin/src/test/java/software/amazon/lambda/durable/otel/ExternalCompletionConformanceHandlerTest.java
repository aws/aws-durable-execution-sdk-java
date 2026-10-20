// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.otel.SpanAttributes.*;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.UserFunctionEndInfo;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ExternalCompletionConformanceHandlerTest {
    private static final String TARGET = "otel-external-target";
    private static final String MARKER = "otel-external-target-observed";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void actualHandlerExportsFirstCompletionBeforeTwoLaterReplays(boolean executionView) throws Exception {
        DeterministicIdGenerator.clearSharedStateForTest();
        DurableSamplingDecision.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
        var classes = compileHandler();
        var workers = Executors.newFixedThreadPool(4);
        try (var loader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            var type =
                    loader.loadClass("software.amazon.lambda.durable.conformance.otel.Otel26ExternalCompletionReplay");
            var handler = (DurableHandler<Map<String, Object>, String>)
                    type.getConstructor().newInstance();
            var exporter = InMemorySpanExporter.create();
            var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
            var options = OtelPluginConfig.builder()
                    .contextExtractor(() -> null)
                    .enableMdc(false)
                    .build();
            DurableExecutionPlugin otel = executionView
                    ? new ExecutionOtelPlugin(provider, options)
                    : new InvocationOtelPlugin(provider, options);
            var ends = new CopyOnWriteArrayList<InvocationEndInfo>();
            var markerBodies = new AtomicInteger();
            var observer = new DurableExecutionPlugin() {
                @Override
                public void onInvocationEnd(InvocationEndInfo info) {
                    ends.add(info);
                }

                @Override
                public void onUserFunctionEnd(UserFunctionEndInfo info) {
                    if (MARKER.equals(info.name())) markerBodies.incrementAndGet();
                }
            };
            var runner = LocalDurableTestRunner.create(
                    new TypeToken<Map<String, Object>>() {},
                    handler::handleRequest,
                    DurableConfig.builder()
                            .withExecutorService(workers)
                            .withPlugins(otel, observer)
                            .build());
            Map<String, Object> event = Map.of("scenario", "external-callback-completion-replay");
            var first = runner.run(event);
            assertEquals(ExecutionStatus.PENDING, first.getStatus());
            var operationId = first.getOperation(TARGET).getId();
            assertEquals(0, terminalCallbacks(exporter, TARGET).size());
            runner.completeCallback(runner.getCallbackId(TARGET), "\"target\"");

            var second = runner.run(event);
            assertEquals(ExecutionStatus.PENDING, second.getStatus());
            assertEquals(1, terminalCallbacks(exporter, TARGET).size(), "first external completion is exported now");
            var terminal = terminalCallbacks(exporter, TARGET).get(0);
            var marker = exporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals(MARKER + " attempt 1"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(
                    terminal.getEndEpochNanos() <= marker.getStartEpochNanos(),
                    "first completion precedes the checkpointed observation body");
            assertEquals(1, markerBodies.get());
            runner.completeCallback(runner.getCallbackId("otel-external-barrier-one-callback"), "\"one\"");

            var third = runner.run(event);
            assertEquals(ExecutionStatus.PENDING, third.getStatus());
            assertEquals(operationId, third.getOperation(TARGET).getId());
            assertEquals(1, terminalCallbacks(exporter, TARGET).size(), "first later replay must not re-export target");
            runner.completeCallback(runner.getCallbackId("otel-external-barrier-two-callback"), "\"two\"");

            var fourth = runner.run(event);
            assertEquals(ExecutionStatus.SUCCEEDED, fourth.getStatus());
            assertEquals("target/one/two", fourth.getResult(String.class));
            assertEquals(operationId, fourth.getOperation(TARGET).getId());
            assertEquals(1, markerBodies.get(), "the completed observation body must not run again");
            assertEquals(
                    1, terminalCallbacks(exporter, TARGET).size(), "second later replay must not re-export target");
            for (var name :
                    List.of(TARGET, "otel-external-barrier-one-callback", "otel-external-barrier-two-callback")) {
                var spans = terminalCallbacks(exporter, name);
                assertEquals(1, spans.size(), "raw exporter records retain duplicate exports if the SDK emits them");
                assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());
            }
            assertEquals(
                    List.of(
                            InvocationStatus.PENDING,
                            InvocationStatus.PENDING,
                            InvocationStatus.PENDING,
                            InvocationStatus.SUCCEEDED),
                    ends.stream().map(InvocationEndInfo::invocationStatus).toList());
            var spans = exporter.getFinishedSpanItems();
            assertEquals(1, spans.stream().map(SpanData::getTraceId).distinct().count());
            assertEquals(
                    4,
                    spans.stream()
                            .filter(span -> span.getName().equals("Invocation"))
                            .count());
            assertEquals(
                    1,
                    spans.stream()
                            .filter(span -> span.getName().equals("Workflow"))
                            .count());
            assertEquals(
                    1,
                    spans.stream()
                            .filter(span -> span.getName().equals(MARKER + " attempt 1"))
                            .count());
            // The local runner does not synthesize service InvocationCompleted history events. The four
            // completed SDK invocations above prove local phases; shared cloud validation checks service gates.
            System.out.println("CASE26 view=" + (executionView ? "execution" : "invocation")
                    + " targetTerminalExports="
                    + terminalCallbacks(exporter, TARGET).size()
                    + " observationBodies=" + markerBodies.get() + " history="
                    + fourth.getHistoryEvents().stream()
                            .map(e -> e.eventId() + ":" + e.eventType())
                            .toList());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            DeterministicIdGenerator.clearSharedStateForTest();
            DurableSamplingDecision.clearSharedStateForTest();
            OtelPluginAutoConfigurationState.resetInstalledForTest();
        }
    }

    private List<SpanData> terminalCallbacks(InMemorySpanExporter exporter, String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(span -> name.equals(span.getAttributes().get(DURABLE_OPERATION_NAME)))
                .filter(span -> "CALLBACK".equals(span.getAttributes().get(DURABLE_OPERATION_TYPE)))
                .filter(span -> "SUCCEEDED".equals(span.getAttributes().get(DURABLE_OPERATION_STATUS)))
                .toList();
    }

    /** Compile the actual standalone deployment sources against this reactor, without copying their handler logic. */
    private Path compileHandler() throws Exception {
        var root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("conformance-tests-otel"))) root = root.getParent();
        assertNotNull(root, "repository root containing deployment handler sources");
        var source =
                root.resolve("conformance-tests-otel/src/main/java/software/amazon/lambda/durable/conformance/otel");
        var classes = Files.createDirectories(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "these tests require the same JDK used to compile the SDK");
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
                        source.resolve("OtelConformanceHandler.java").toString(),
                        source.resolve("Otel26ExternalCompletionReplay.java").toString()));
        return classes;
    }
}
