// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.otel.SpanAttributes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

@Timeout(20)
class ChainedInvokeConformanceHandlerTest {
    private static final String INVOKE = "otel-invoke";
    private static final String BARRIER = "otel-invoke-replay-barrier";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void actualHandlerReplaysInvokeResultWithoutAnotherTerminalExport(boolean executionView) throws Exception {
        DeterministicIdGenerator.clearSharedStateForTest();
        DurableSamplingDecision.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
        var classes = compileHandler();
        var workers = Executors.newFixedThreadPool(4);
        var exporter = InMemorySpanExporter.create();
        try (var loader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            var handler = (DurableHandler<Map<String, Object>, Map<String, Object>>)
                    loader.loadClass("software.amazon.lambda.durable.conformance.otel.Otel11ChainedInvoke")
                            .getConstructor()
                            .newInstance();
            var ends = new CopyOnWriteArrayList<InvocationEndInfo>();
            var runner = LocalDurableTestRunner.create(
                    new TypeToken<Map<String, Object>>() {},
                    handler::handleRequest,
                    configuration(executionView, exporter, workers, ends));
            Map<String, Object> input = Map.of("scenario", "chained-invoke", "nested", Map.of("value", 42));
            var first = runner.run(input);
            assertEquals(ExecutionStatus.PENDING, first.getStatus());
            var invokeId = first.getOperation(INVOKE).getId();
            assertTrue(terminalInvokes(exporter).isEmpty());
            runner.completeChainedInvoke(INVOKE, new ObjectMapper().writeValueAsString(input));

            var second = runner.run(input);
            assertEquals(ExecutionStatus.PENDING, second.getStatus());
            assertEquals(invokeId, second.getOperation(INVOKE).getId());
            assertEquals(1, terminalInvokes(exporter).size());
            var completed = terminalInvokes(exporter).get(0);
            assertEquals(StatusCode.OK, completed.getStatus().getStatusCode());
            runner.completeCallback(runner.getCallbackId(BARRIER + "-callback"), "\"resume\"");

            var third = runner.run(input);
            assertEquals(ExecutionStatus.SUCCEEDED, third.getStatus());
            assertEquals(input, third.getResult(new TypeToken<Map<String, Object>>() {}));
            assertEquals(invokeId, third.getOperation(INVOKE).getId());
            assertEquals(List.of(completed), terminalInvokes(exporter));
            assertEquals(
                    List.of(InvocationStatus.PENDING, InvocationStatus.PENDING, InvocationStatus.SUCCEEDED),
                    ends.stream().map(InvocationEndInfo::invocationStatus).toList());
            assertEquals(
                    1,
                    third.getHistoryEvents().stream()
                            .filter(event -> "ChainedInvokeStarted".equals(event.eventTypeAsString()))
                            .count());
            assertEquals(
                    1,
                    third.getHistoryEvents().stream()
                            .filter(event -> "ChainedInvokeSucceeded".equals(event.eventTypeAsString()))
                            .count());
            assertEquals(
                    1,
                    third.getHistoryEvents().stream()
                            .filter(event -> "CallbackSucceeded".equals(event.eventTypeAsString()))
                            .count());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            exporter.close();
            DeterministicIdGenerator.clearSharedStateForTest();
            DurableSamplingDecision.clearSharedStateForTest();
            OtelPluginAutoConfigurationState.resetInstalledForTest();
        }
    }

    private DurableConfig configuration(
            boolean executionView,
            InMemorySpanExporter exporter,
            ExecutorService workers,
            List<InvocationEndInfo> ends) {
        var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var options = OtelPluginConfig.builder()
                .contextExtractor(() -> null)
                .enableMdc(false)
                .build();
        DurableExecutionPluginFactory otel = executionView
                ? ExecutionOtelPlugin.factory(provider, options)
                : InvocationOtelPlugin.factory(provider, options);
        var observer = new DurableExecutionPlugin() {
            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                ends.add(info);
            }
        };
        return DurableConfig.builder()
                .withExecutorService(workers)
                .withPlugins(otel, ignored -> observer)
                .build();
    }

    private List<SpanData> terminalInvokes(InMemorySpanExporter exporter) {
        return exporter.getFinishedSpanItems().stream()
                .filter(span -> INVOKE.equals(span.getName()))
                .filter(span -> "SUCCEEDED".equals(span.getAttributes().get(DURABLE_OPERATION_STATUS)))
                .toList();
    }

    private Path compileHandler() throws Exception {
        var root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("conformance-tests-otel"))) root = root.getParent();
        assertNotNull(root, "repository root containing deployment handler sources");
        var source =
                root.resolve("conformance-tests-otel/src/main/java/software/amazon/lambda/durable/conformance/otel");
        var classes = Files.createDirectories(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
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
                        source.resolve("Otel11ChainedInvoke.java").toString()));
        return classes;
    }
}
