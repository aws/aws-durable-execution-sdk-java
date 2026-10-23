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
class SuspendedChildConformanceHandlerTest {
    private static final String INVOKE = "otel-suspended-invoke";
    private static final String WAIT = "otel-suspended-target-wait";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualChildSuspendsAndResumesBeforeCallerCompletes(boolean executionView) throws Exception {
        DeterministicIdGenerator.clearSharedStateForTest();
        DurableSamplingDecision.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
        var classes = compileHandler();
        var workers = Executors.newFixedThreadPool(4);
        var exporter = InMemorySpanExporter.create();
        try (var loader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            verifyHandlers(executionView, loader, exporter, workers);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            exporter.close();
            DeterministicIdGenerator.clearSharedStateForTest();
            DurableSamplingDecision.clearSharedStateForTest();
            OtelPluginAutoConfigurationState.resetInstalledForTest();
        }
    }

    private void verifyHandlers(
            boolean executionView, URLClassLoader loader, InMemorySpanExporter exporter, ExecutorService workers)
            throws Exception {
        var ends = new CopyOnWriteArrayList<InvocationEndInfo>();
        var childEnds = new CopyOnWriteArrayList<InvocationEndInfo>();
        var handler = loadHandler(loader, "Otel27ChainedInvokeSuspend");
        var childHandler = loadHandler(loader, "Otel27InvokeTarget");
        var runner = LocalDurableTestRunner.create(
                new TypeToken<Map<String, Object>>() {},
                handler::handleRequest,
                configuration(executionView, exporter, workers, ends));
        var child = LocalDurableTestRunner.create(
                new TypeToken<Map<String, Object>>() {},
                childHandler::handleRequest,
                configuration(executionView, exporter, workers, childEnds));
        verifyExecution(runner, child);
        for (var invocations : List.of(ends, childEnds)) {
            assertEquals(
                    List.of(InvocationStatus.PENDING, InvocationStatus.SUCCEEDED),
                    invocations.stream()
                            .map(InvocationEndInfo::invocationStatus)
                            .toList());
        }
        verifyWaitTelemetry(executionView, exporter);
        assertEquals(1, terminalInvokes(exporter).size());
    }

    @SuppressWarnings("unchecked")
    private DurableHandler<Map<String, Object>, Map<String, Object>> loadHandler(URLClassLoader loader, String name)
            throws Exception {
        return (DurableHandler<Map<String, Object>, Map<String, Object>>)
                loader.loadClass("software.amazon.lambda.durable.conformance.otel." + name)
                        .getConstructor()
                        .newInstance();
    }

    private void verifyExecution(
            LocalDurableTestRunner<Map<String, Object>, Map<String, Object>> runner,
            LocalDurableTestRunner<Map<String, Object>, Map<String, Object>> child)
            throws Exception {
        Map<String, Object> input = Map.of("scenario", "chained-invoke-suspend", "nested", Map.of("value", 42));
        var first = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, first.getStatus());
        var invokeId = first.getOperation(INVOKE).getId();
        var childFirst = child.run(input);
        assertEquals(ExecutionStatus.PENDING, childFirst.getStatus());
        var waitId = childFirst.getOperation(WAIT).getId();
        child.advanceTime();
        var childLast = child.run(input);
        assertEquals(ExecutionStatus.SUCCEEDED, childLast.getStatus());
        assertEquals(waitId, childLast.getOperation(WAIT).getId());
        var childResult = childLast.getResult(new TypeToken<Map<String, Object>>() {});
        assertEquals(input, childResult);
        runner.completeChainedInvoke(INVOKE, new ObjectMapper().writeValueAsString(childResult));
        var last = runner.run(input);
        assertEquals(ExecutionStatus.SUCCEEDED, last.getStatus());
        assertEquals(input, last.getResult(new TypeToken<Map<String, Object>>() {}));
        assertEquals(invokeId, last.getOperation(INVOKE).getId());
    }

    private void verifyWaitTelemetry(boolean executionView, InMemorySpanExporter exporter) {
        var waits = exporter.getFinishedSpanItems().stream()
                .filter(span -> WAIT.equals(span.getName()))
                .toList();
        assertEquals(executionView ? 1 : 2, waits.size());
        assertEquals(
                1,
                waits.stream()
                        .map(span -> span.getAttributes().get(DURABLE_OPERATION_ID))
                        .distinct()
                        .count());
        var completed = waits.get(waits.size() - 1);
        assertEquals(StatusCode.OK, completed.getStatus().getStatusCode());
        if (executionView) {
            var childArn = completed.getAttributes().get(DURABLE_EXECUTION_ARN);
            var resumed = exporter.getFinishedSpanItems().stream()
                    .filter(span -> "Invocation".equals(span.getName()))
                    .filter(span -> childArn.equals(span.getAttributes().get(DURABLE_EXECUTION_ARN)))
                    .filter(span -> Boolean.FALSE.equals(span.getAttributes().get(DURABLE_FIRST_INVOCATION)))
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                    resumed.getSpanId(),
                    completed.getLinks().get(0).getSpanContext().getSpanId());
        } else {
            assertEquals(
                    waits.get(0).getSpanId(),
                    completed.getLinks().get(0).getSpanContext().getSpanId());
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
                        source.resolve("Otel27ChainedInvokeSuspend.java").toString(),
                        source.resolve("Otel27InvokeTarget.java").toString()));
        return classes;
    }
}
