// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.otel.SpanAttributes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.testing.TestOperation;
import software.amazon.lambda.durable.testing.TestResult;

@Timeout(20)
class CaughtFailureConformanceHandlerTest {
    private static final String FAILED = "otel-failed-callback";
    private static final String LEAF = FAILED + "-callback";
    private static final String MARKER = FAILED + "-observed";
    private static final String ONE = FAILED + "-barrier-one";
    private static final String TWO = FAILED + "-barrier-two";
    private static final String CASE25 = "Otel25CaughtCallbackFailureReplay";
    private static final Map<String, Object> INPUT = Map.of("scenario", "caught-callback-failure-replay");

    @TempDir
    Path directory;

    static Stream<Arguments> viewsAndErrors() {
        return Stream.of(false, true).flatMap(view -> Stream.of(false, true).map(empty -> Arguments.of(view, empty)));
    }

    @ParameterizedTest
    @MethodSource("viewsAndErrors")
    void actualCase25CatchesFirstFailureAndTwoCachedReplays(boolean executionView, boolean emptyContainer)
            throws Exception {
        try (var fixture = fixture(executionView, CASE25)) {
            var error = emptyContainer ? ErrorObject.builder().build() : null;
            var first = fixture.run(INPUT);
            assertEquals(ExecutionStatus.PENDING, first.getStatus());
            var failedId = first.getOperation(LEAF).getId();
            var contextId = first.getOperation(FAILED).getId();
            assertTrue(fixture.terminal(LEAF, "FAILED").isEmpty());
            assertEquals(0, fixture.bodies(MARKER));
            fixture.runner.failCallback(fixture.runner.getCallbackId(LEAF), error);

            var second = fixture.run(INPUT);
            assertEquals(ExecutionStatus.PENDING, second.getStatus());
            assertEquals(1, fixture.terminal(LEAF, "FAILED").size(), "publish first terminal failure in invocation2");
            assertEquals(1, fixture.bodies(MARKER));
            assertEquals("caught", second.getOperation(MARKER).getStepResult(String.class));
            var leaf = fixture.terminal(LEAF, "FAILED").get(0);
            assertEquals(StatusCode.ERROR, leaf.getStatus().getStatusCode());
            assertTrue(leaf.getEvents().isEmpty(), "omitted/empty backend details must not invent an exception event");
            assertEquals(failedId, leaf.getAttributes().get(DURABLE_OPERATION_ID));
            assertEquals("Callback", leaf.getAttributes().get(DURABLE_OPERATION_SUBTYPE));
            var marker = fixture.exporter.getFinishedSpanItems().stream()
                    .filter(span -> (MARKER + " attempt 1").equals(span.getName()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(leaf.getEndEpochNanos() <= marker.getStartEpochNanos());
            fixture.runner.completeCallback(fixture.runner.getCallbackId(ONE + "-callback"), "\"one\"");

            var third = fixture.run(INPUT);
            assertEquals(ExecutionStatus.PENDING, third.getStatus());
            assertEquals(failedId, third.getOperation(LEAF).getId());
            assertEquals(1, fixture.terminal(LEAF, "FAILED").size());
            assertEquals(1, fixture.bodies(MARKER));
            fixture.runner.completeCallback(fixture.runner.getCallbackId(TWO + "-callback"), "\"two\"");
            var fourth = fixture.run(INPUT);
            fixture.writeEvidence(
                    (executionView ? "execution" : "invocation") + (emptyContainer ? "-empty" : "-omitted"));
            assertEquals(ExecutionStatus.SUCCEEDED, fourth.getStatus());
            assertEquals("caught/one/two", fourth.getResult(String.class));
            assertTrue(fourth.getError().isEmpty());
            assertEquals(error, fourth.getOperation(LEAF).getCallbackDetails().error());
            assertEquals(failedId, fourth.getOperation(LEAF).getId());
            assertEquals(contextId, fourth.getOperation(FAILED).getId());
            assertEquals(1, fixture.bodies(MARKER));
            for (var name : List.of(FAILED, ONE, TWO)) assertEquals(1, fixture.bodies(name + "-submitter"));
            assertEquals(1, fixture.terminal(LEAF, "FAILED").size(), "raw exports must retain duplicates if present");
            assertEquals(1, fixture.terminal(ONE + "-callback", "SUCCEEDED").size());
            assertEquals(1, fixture.terminal(TWO + "-callback", "SUCCEEDED").size());
            assertEquals(
                    List.of(
                            InvocationStatus.PENDING,
                            InvocationStatus.PENDING,
                            InvocationStatus.PENDING,
                            InvocationStatus.SUCCEEDED),
                    fixture.ends.stream()
                            .map(InvocationEndInfo::invocationStatus)
                            .toList());
            assertEquals(
                    List.of(true, false, false, false),
                    fixture.starts.stream()
                            .map(InvocationInfo::isFirstInvocation)
                            .toList());
            assertEquals(List.of(CallbackFailedException.class.getName()), fixture.firstFailureTypes);
            assertEquals(
                    2, fixture.bodies(FAILED), "cached failed-child replay must not run its body in invocations3/4");
            verifyTopology(fixture, executionView, leaf, contextId);
            verifyLocalHistory(fourth);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unexpectedCallbackSuccessFailsBeforeMarker(boolean executionView) throws Exception {
        try (var fixture = fixture(executionView, CASE25)) {
            assertEquals(ExecutionStatus.PENDING, fixture.run(INPUT).getStatus());
            fixture.runner.completeCallback(fixture.runner.getCallbackId(LEAF), "\"unexpected\"");
            var result = fixture.run(INPUT);
            assertEquals(ExecutionStatus.FAILED, result.getStatus());
            assertTrue(result.getError().orElseThrow().errorMessage().contains("Expected the first callback to fail"));
            assertNull(result.getOperation(MARKER));
            assertEquals(0, fixture.bodies(MARKER));
        }
    }

    static Stream<Arguments> barriers() {
        return Stream.of(false, true).flatMap(view -> Stream.of(ONE, TWO).map(name -> Arguments.of(view, name)));
    }

    @ParameterizedTest
    @MethodSource("barriers")
    void barrierFailuresRemainOutsideTheExpectedFailureCatch(boolean executionView, String barrier) throws Exception {
        try (var fixture = fixture(executionView, CASE25)) {
            assertEquals(ExecutionStatus.PENDING, fixture.run(INPUT).getStatus());
            fixture.runner.failCallback(fixture.runner.getCallbackId(LEAF), null);
            assertEquals(ExecutionStatus.PENDING, fixture.run(INPUT).getStatus());
            if (TWO.equals(barrier)) {
                fixture.runner.completeCallback(fixture.runner.getCallbackId(ONE + "-callback"), "\"one\"");
                assertEquals(ExecutionStatus.PENDING, fixture.run(INPUT).getStatus());
            }
            var error = ErrorObject.builder()
                    .errorType("BarrierFailure")
                    .errorMessage("must escape")
                    .build();
            fixture.runner.failCallback(fixture.runner.getCallbackId(barrier + "-callback"), error);
            var result = fixture.run(INPUT);
            assertEquals(ExecutionStatus.FAILED, result.getStatus());
            assertEquals(error, result.getError().orElseThrow());
            assertEquals(1, fixture.bodies(MARKER));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void case17StillPropagatesItsSuppliedCallbackFailure(boolean executionView) throws Exception {
        try (var fixture = fixture(executionView, "Otel17WaitForCallbackFailure")) {
            var input = Map.<String, Object>of("scenario", "wait-for-callback-failure");
            assertEquals(ExecutionStatus.PENDING, fixture.run(input).getStatus());
            var error = ErrorObject.builder()
                    .errorType("Case17Failure")
                    .errorMessage("unhandled")
                    .build();
            fixture.runner.failCallback(fixture.runner.getCallbackId(LEAF), error);
            var result = fixture.run(input);
            assertEquals(ExecutionStatus.FAILED, result.getStatus());
            assertEquals(error, result.getError().orElseThrow());
            assertNull(result.getOperation(MARKER));
            var leaf = fixture.terminal(LEAF, "FAILED").get(0);
            assertEquals(StatusCode.ERROR, leaf.getStatus().getStatusCode());
            assertEquals(1, leaf.getEvents().size());
        }
    }

    private static void verifyTopology(Fixture fixture, boolean execution, SpanData leaf, String contextId) {
        var spans = fixture.exporter.getFinishedSpanItems();
        var invocations =
                spans.stream().filter(s -> "Invocation".equals(s.getName())).toList();
        assertEquals(4, invocations.size());
        assertEquals(
                List.of(true, false, false, false),
                invocations.stream()
                        .map(span -> span.getAttributes().get(DURABLE_FIRST_INVOCATION))
                        .toList());
        assertEquals(
                List.of("PENDING", "PENDING", "PENDING", "SUCCEEDED"),
                invocations.stream()
                        .map(span -> span.getAttributes().get(DURABLE_INVOCATION_STATUS))
                        .toList());
        for (var span : invocations)
            assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
        var workflow =
                spans.stream().filter(s -> "Workflow".equals(s.getName())).toList();
        assertEquals(1, workflow.size());
        assertEquals(StatusCode.OK, workflow.get(0).getStatus().getStatusCode());
        assertEquals("SUCCEEDED", workflow.get(0).getAttributes().get(DURABLE_EXECUTION_STATUS));
        assertEquals(1, spans.stream().map(SpanData::getTraceId).distinct().count());
        var parent = spans.stream()
                .filter(s -> leaf.getParentSpanId().equals(s.getSpanId()))
                .findFirst()
                .orElseThrow();
        assertEquals(contextId, parent.getAttributes().get(DURABLE_OPERATION_ID));
        if (execution)
            assertTrue(leaf.getLinks().stream()
                    .anyMatch(link -> link.getSpanContext()
                            .getSpanId()
                            .equals(invocations.get(1).getSpanId())));
        else {
            assertEquals(invocations.get(1).getSpanId(), parent.getParentSpanId());
            assertTrue(invocations.get(1).getStartEpochNanos() <= parent.getStartEpochNanos());
            assertTrue(parent.getEndEpochNanos() <= invocations.get(1).getEndEpochNanos());
        }
    }

    private static void verifyLocalHistory(TestResult<String> result) {
        // The local runner does not synthesize execution or invocation boundary history events.
        assertEquals(
                List.of(
                        "ContextStarted",
                        "CallbackStarted",
                        "StepStarted",
                        "StepSucceeded",
                        "CallbackFailed",
                        "ContextFailed",
                        "StepStarted",
                        "StepSucceeded",
                        "ContextStarted",
                        "CallbackStarted",
                        "StepStarted",
                        "StepSucceeded",
                        "CallbackSucceeded",
                        "ContextSucceeded",
                        "ContextStarted",
                        "CallbackStarted",
                        "StepStarted",
                        "StepSucceeded",
                        "CallbackSucceeded",
                        "ContextSucceeded"),
                result.getHistoryEvents().stream()
                        .map(event -> event.eventType().toString())
                        .toList());
        var parents = new LinkedHashMap<String, String>();
        parents.put(MARKER, null);
        for (var name : List.of(FAILED, ONE, TWO)) {
            parents.put(name, null);
            parents.put(name + "-callback", result.getOperation(name).getId());
            parents.put(name + "-submitter", result.getOperation(name).getId());
        }
        for (var event : result.getHistoryEvents()) {
            assertEquals(result.getOperation(event.name()).getId(), event.id());
            assertTrue(parents.containsKey(event.name()));
            assertEquals(parents.get(event.name()), event.parentId());
        }
        assertEquals(
                "\"one\"",
                result.getOperation(ONE + "-callback").getCallbackDetails().result());
        assertEquals(
                "\"two\"",
                result.getOperation(TWO + "-callback").getCallbackDetails().result());
    }

    private Fixture fixture(boolean execution, String handlerName) throws Exception {
        DeterministicIdGenerator.clearSharedStateForTest();
        DurableSamplingDecision.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
        return new Fixture(execution, compileHandlers(), handlerName);
    }

    private Path compileHandlers() throws Exception {
        var root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("conformance-tests-otel"))) root = root.getParent();
        assertNotNull(root);
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
                        source.resolve(CASE25 + ".java").toString(),
                        source.resolve("Otel17WaitForCallbackFailure.java").toString()));
        return classes;
    }

    private static final class Fixture implements AutoCloseable {
        private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
        private final ExecutorService workers = Executors.newFixedThreadPool(4);
        private final URLClassLoader loader;
        private final LocalDurableTestRunner<Map<String, Object>, String> runner;
        private final List<InvocationInfo> starts = new CopyOnWriteArrayList<>();
        private final List<InvocationEndInfo> ends = new CopyOnWriteArrayList<>();
        private final Map<String, AtomicInteger> bodies = new ConcurrentHashMap<>();
        private final List<String> firstFailureTypes = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> phases = new ArrayList<>();

        @SuppressWarnings("unchecked")
        private Fixture(boolean execution, Path classes, String handlerName) throws Exception {
            loader = new URLClassLoader(
                    new URL[] {classes.toUri().toURL()}, getClass().getClassLoader());
            var type = loader.loadClass("software.amazon.lambda.durable.conformance.otel." + handlerName);
            var handler = (DurableHandler<Map<String, Object>, String>)
                    type.getConstructor().newInstance();
            var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
            var options = OtelPluginConfig.builder()
                    .enableMdc(false)
                    .contextExtractor(() -> null)
                    .build();
            DurableExecutionPlugin otel = execution
                    ? new ExecutionOtelPlugin(provider, options)
                    : new InvocationOtelPlugin(provider, options);
            var observer = new DurableExecutionPlugin() {
                public void onInvocationStart(InvocationInfo info) {
                    starts.add(info);
                }

                public void onInvocationEnd(InvocationEndInfo info) {
                    ends.add(info);
                }

                public void onUserFunctionStart(UserFunctionStartInfo info) {
                    if (info.name() != null)
                        bodies.computeIfAbsent(info.name(), ignored -> new AtomicInteger())
                                .incrementAndGet();
                }

                public void onUserFunctionEnd(UserFunctionEndInfo info) {
                    if (FAILED.equals(info.name()) && info.error() instanceof CallbackFailedException)
                        firstFailureTypes.add(info.error().getClass().getName());
                }
            };
            runner = LocalDurableTestRunner.create(
                    new TypeToken<Map<String, Object>>() {},
                    handler::handleRequest,
                    DurableConfig.builder()
                            .withExecutorService(workers)
                            .withPlugins(otel, observer)
                            .build());
        }

        private TestResult<String> run(Map<String, Object> input) {
            var result = runner.run(input);
            var phase = new LinkedHashMap<String, Object>();
            phase.put("invocation", phases.size() + 1);
            phase.put("status", result.getStatus().toString());
            phase.put(
                    "operations",
                    result.getOperations().stream()
                            .map(CaughtFailureConformanceHandlerTest::operation)
                            .toList());
            if (result.isSucceeded()) phase.put("result", result.getResult(String.class));
            phase.put(
                    "history",
                    result.getHistoryEvents().stream()
                            .map(CaughtFailureConformanceHandlerTest::sdkValue)
                            .toList());
            phase.put(
                    "spans",
                    exporter.getFinishedSpanItems().stream()
                            .map(CaughtFailureConformanceHandlerTest::span)
                            .toList());
            phase.put("bodyCounts", bodyCounts());
            phase.put("firstFailureTypes", List.copyOf(firstFailureTypes));
            phases.add(phase);
            return result;
        }

        private int bodies(String name) {
            return bodies.getOrDefault(name, new AtomicInteger()).get();
        }

        private Map<String, Integer> bodyCounts() {
            var counts = new LinkedHashMap<String, Integer>();
            bodies.forEach((name, count) -> counts.put(name, count.get()));
            return counts;
        }

        private List<SpanData> terminal(String name, String status) {
            return exporter.getFinishedSpanItems().stream()
                    .filter(s -> name.equals(s.getAttributes().get(DURABLE_OPERATION_NAME)))
                    .filter(s -> "CALLBACK".equals(s.getAttributes().get(DURABLE_OPERATION_TYPE)))
                    .filter(s -> status.equals(s.getAttributes().get(DURABLE_OPERATION_STATUS)))
                    .toList();
        }

        private void writeEvidence(String label) throws Exception {
            var output = System.getProperty("java769.case25EvidenceDir");
            if (output != null) {
                var dir = Files.createDirectories(Path.of(output));
                new ObjectMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValue(dir.resolve(label + ".json").toFile(), phases);
            }
            System.out.println("CASE25 " + label + " phases="
                    + phases.stream().map(p -> p.get("status")).toList() + " bodies=" + bodyCounts() + " exportedSpans="
                    + exporter.getFinishedSpanItems().size());
        }

        public void close() throws Exception {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            loader.close();
            exporter.close();
            DeterministicIdGenerator.clearSharedStateForTest();
            DurableSamplingDecision.clearSharedStateForTest();
            OtelPluginAutoConfigurationState.resetInstalledForTest();
        }
    }

    private static Map<String, Object> operation(TestOperation operation) {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", operation.getId());
        out.put("name", operation.getName());
        out.put("type", operation.getType());
        out.put("subType", operation.getSubtype());
        out.put("status", operation.getStatus());
        out.put("callbackDetails", sdkValue(operation.getCallbackDetails()));
        out.put("contextDetails", sdkValue(operation.getContextDetails()));
        out.put("stepDetails", sdkValue(operation.getStepDetails()));
        return out;
    }

    private static Object sdkValue(Object value) {
        if (value instanceof ErrorObject error) {
            var fields = new LinkedHashMap<String, Object>();
            if (error.errorType() != null) fields.put("ErrorType", error.errorType());
            if (error.errorMessage() != null) fields.put("ErrorMessage", error.errorMessage());
            if (error.errorData() != null) fields.put("ErrorData", error.errorData());
            if (error.hasStackTrace()) fields.put("StackTrace", error.stackTrace());
            return fields;
        }
        if (value instanceof SdkPojo pojo) {
            var map = new LinkedHashMap<String, Object>();
            for (var field : pojo.sdkFields()) {
                var item = field.getValueOrDefault(pojo);
                if (item != null) map.put(field.memberName(), sdkValue(item));
            }
            return map;
        }
        if (value instanceof List<?> list)
            return list.stream()
                    .map(CaughtFailureConformanceHandlerTest::sdkValue)
                    .toList();
        if (value instanceof Instant instant) return instant.toString();
        return value;
    }

    private static Map<String, Object> attributes(Attributes attributes) {
        var map = new LinkedHashMap<String, Object>();
        attributes.forEach((key, value) -> map.put(key.getKey(), value));
        return map;
    }

    private static Map<String, Object> span(SpanData span) {
        var out = new LinkedHashMap<String, Object>();
        out.put("name", span.getName());
        out.put("traceId", span.getTraceId());
        out.put("spanId", span.getSpanId());
        out.put("parentSpanId", span.getParentSpanId());
        out.put("startEpochNanos", span.getStartEpochNanos());
        out.put("endEpochNanos", span.getEndEpochNanos());
        out.put("status", span.getStatus().getStatusCode().toString());
        out.put("description", span.getStatus().getDescription());
        out.put("attributes", attributes(span.getAttributes()));
        out.put(
                "events",
                span.getEvents().stream()
                        .map(e -> Map.of(
                                "name",
                                e.getName(),
                                "epochNanos",
                                e.getEpochNanos(),
                                "attributes",
                                attributes(e.getAttributes())))
                        .toList());
        out.put(
                "links",
                span.getLinks().stream()
                        .map(l -> Map.of(
                                "traceId",
                                l.getSpanContext().getTraceId(),
                                "spanId",
                                l.getSpanContext().getSpanId(),
                                "attributes",
                                attributes(l.getAttributes())))
                        .toList());
        return out;
    }
}
