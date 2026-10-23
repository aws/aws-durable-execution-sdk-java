// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.lmi;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import io.opentelemetry.api.trace.Span;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

class OtelInvocationTest {
    private static final String HEADER = "Root=1-6955b900-123456789012345678901234;Parent=1234567890123456;Sampled=1";
    private final ExecutorService workers = Executors.newCachedThreadPool();

    @AfterEach
    void stopWorkers() throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @CsvSource({"invocation,1", "invocation,0", "execution,1", "execution,0"})
    void runtimeCarrierSamplingAndOwnerCleanup(String view, String sampled) {
        try (var capture = new TraceCapture();
                var runtime = new OtelRuntime(view)) {
            var request = new Request("sampled", view, "success", HEADER.substring(0, HEADER.length() - 1) + sampled);
            var output = request.run(runtime, (value, context) -> {
                assertTrue(Span.current().getSpanContext().isValid(), "Handler scope was not forwarded");
                return context.step("saved", String.class, step -> value.marker());
            });
            assertEquals(ExecutionStatus.SUCCEEDED, output.status());
            assertEquals(Set.of(Thread.currentThread().getName()), request.headerThreads);
            var exported = spans(capture.events());
            assertEquals(sampled.equals("0"), exported.isEmpty());
            if (sampled.equals("1")) assertSpans(exported, request.arn);
            assertCleanup(capture.events());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invocation", "execution"})
    void realSuspendResumeDoesNotRepeatCompletedWork(String view) {
        var bodies = new AtomicInteger();
        try (var capture = new TraceCapture();
                var runtime = new OtelRuntime(view)) {
            var request = new Request("replay", view, "success", HEADER);
            BiFunction<FixtureInput, DurableContext, String> handler = (input, context) -> {
                var value = context.step("saved", String.class, step -> {
                    bodies.incrementAndGet();
                    return input.marker();
                });
                context.wait("pause", Duration.ofSeconds(1));
                return value;
            };
            assertEquals(ExecutionStatus.PENDING, request.run(runtime, handler).status());
            assertTrue(request.storage.advanceTime());
            assertEquals(
                    ExecutionStatus.SUCCEEDED, request.run(runtime, handler).status());
            assertEquals(1, bodies.get());
            assertEquals(
                    1,
                    spans(capture.events()).stream()
                            .filter(s -> s.get("name").equals("Workflow"))
                            .count());
            assertCleanup(capture.events());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "invocation,null", "invocation,empty", "invocation,invalid", "invocation,throw",
        "execution,null", "execution,empty", "execution,invalid", "execution,throw"
    })
    void authoritativeMissingOrInvalidCarrierCannotBorrowGlobalState(String view, String value) {
        var key = "com.amazonaws.xray.traceHeader";
        var previous = System.getProperty(key);
        System.setProperty(key, HEADER);
        try (var capture = new TraceCapture();
                var runtime = new OtelRuntime(view)) {
            var header = value.equals("null") ? null : value.equals("empty") ? "" : value;
            var request = new Request("missing-" + value, view, "success", header);
            assertEquals(
                    ExecutionStatus.SUCCEEDED,
                    request.run(
                                    runtime,
                                    (input, context) -> context.step("saved", String.class, step -> input.marker()))
                            .status());
            var spans = spans(capture.events());
            assertFalse(spans.isEmpty());
            assertTrue(spans.stream().anyMatch(span -> "DurableExecutionRoot".equals(span.get("name"))));
            assertTrue(
                    spans.stream().noneMatch(span -> "6955b900123456789012345678901234".equals(span.get("traceId"))));
            assertEquals(HEADER, System.getProperty(key));
            assertCleanup(capture.events());
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invocation", "execution"})
    void overlappingOwnersKeepContextAndProviderIndependent(String view) throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var capture = new TraceCapture();
                var runtime = new OtelRuntime(view)) {
            var first = new Request("first", view, "success", HEADER);
            var second = new Request(
                    "second", view, "success", HEADER.replace("123456789012345678901234", "abcdefabcdefabcdefabcdef"));
            BiFunction<FixtureInput, DurableContext, String> handler =
                    (input, context) -> context.step("same-name", String.class, step -> {
                        entered.countDown();
                        await(release);
                        return input.marker();
                    });
            var one = workers.submit(() -> first.run(runtime, handler));
            var two = workers.submit(() -> second.run(runtime, handler));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            assertEquals(
                    ExecutionStatus.SUCCEEDED, one.get(10, TimeUnit.SECONDS).status());
            assertEquals(
                    ExecutionStatus.SUCCEEDED, two.get(10, TimeUnit.SECONDS).status());
            assertOwners(capture.events());
            assertCleanup(capture.events());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invocation", "execution"})
    void failedExporterDoesNotPoisonTheNextInvocation(String view) {
        try (var capture = new TraceCapture();
                var runtime = new OtelRuntime(view)) {
            var failed = new Request("failed-export", view, "failure", HEADER);
            var healthy = new Request("healthy", view, "success", HEADER);
            BiFunction<FixtureInput, DurableContext, String> handler =
                    (input, context) -> context.step("saved", String.class, step -> input.marker());
            assertEquals(ExecutionStatus.SUCCEEDED, failed.run(runtime, handler).status());
            assertEquals(
                    ExecutionStatus.SUCCEEDED, healthy.run(runtime, handler).status());
            assertTrue(capture.events().stream().anyMatch(event -> "failure".equals(event.get("outcome"))));
            assertCleanup(capture.events());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invocation", "execution"})
    void flushBudgetCanReturnBeforeControlledExportCompletes(String view) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var capture = new TraceCapture();
                var runtime = new OtelRuntime(view, trace -> {
                    entered.countDown();
                    await(release);
                })) {
            var request = new Request("held", view, "held", HEADER);
            var start = System.nanoTime();
            var result = workers.submit(() -> request.run(
                    runtime, (input, context) -> context.step("saved", String.class, step -> input.marker())));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(
                        ExecutionStatus.SUCCEEDED,
                        result.get(8, TimeUnit.SECONDS).status());
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(8));
                assertTrue(spans(capture.events()).isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Fixture was not released");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static List<Map<?, ?>> spans(List<Map<String, Object>> events) {
        return events.stream()
                .filter(e -> "OTEL_EXPORT".equals(e.get("kind")))
                .<Map<?, ?>>map(e -> (Map<?, ?>) e.get("span"))
                .toList();
    }

    private static void assertSpans(List<Map<?, ?>> spans, String arn) {
        assertFalse(spans.isEmpty());
        for (var span : spans) {
            assertEquals("6955b900123456789012345678901234", span.get("traceId"));
            assertEquals(arn, ((Map<?, ?>) span.get("attributes")).get("durable.execution.arn"));
            if ("Invocation".equals(span.get("name"))) assertEquals("1234567890123456", span.get("parentId"));
        }
    }

    private static void assertCleanup(List<Map<String, Object>> events) {
        var ends = events.stream().filter(e -> "OTEL_END".equals(e.get("kind"))).toList();
        assertFalse(ends.isEmpty());
        for (var end : ends) {
            assertEquals(true, end.get("contextRestored"));
            assertEquals(true, end.get("mdcRestored"));
            assertEquals(end.get("ownerThread"), end.get("endThread"));
            assertEquals(0, ((Number) end.get("exporterShutdowns")).intValue());
        }
    }

    private static void assertOwners(List<Map<String, Object>> events) {
        for (var event : events) {
            if ("OTEL_EXPORT".equals(event.get("kind"))) {
                var span = (Map<?, ?>) event.get("span");
                var attributes = (Map<?, ?>) span.get("attributes");
                assertEquals(event.get("executionArn"), attributes.get("durable.execution.arn"));
            }
        }
    }

    private final class Request {
        private final LocalMemoryExecutionClient storage = new LocalMemoryExecutionClient();
        private final Set<String> headerThreads = ConcurrentHashMap.newKeySet();
        private final Instant start = Instant.now();
        private final FixtureInput value;
        private final String arn;
        private final String header;
        private int attempt;

        Request(String marker, String view, String mode, String header) {
            value = new FixtureInput("unit", marker, "success", marker, null, null, 1, 1000, view, mode, null);
            arn = "arn:aws:lambda:us-west-2:123456789012:function:lmi:1/durable-execution/" + marker + "/execution";
            this.header = header;
        }

        DurableExecutionOutput run(OtelRuntime runtime, BiFunction<FixtureInput, DurableContext, String> handler) {
            var context = context();
            var trace = new InvocationTrace(value, context, arn);
            runtime.enter(trace, context);
            var config = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withDurableExecutionClient(storage)
                    .withPlugins(runtime.forInvocation(trace))
                    .build();
            MDC.put("lmi-existing", value.marker());
            try {
                return DurableExecutor.execute(input(), context, TypeToken.get(FixtureInput.class), handler, config);
            } finally {
                assertEquals(value.marker(), MDC.get("lmi-existing"));
                MDC.remove("lmi-existing");
            }
        }

        private DurableExecutionInput input() {
            var root = Operation.builder()
                    .id("execution")
                    .type(OperationType.EXECUTION)
                    .status(OperationStatus.STARTED)
                    .startTimestamp(start)
                    .executionDetails(ExecutionDetails.builder()
                            .inputPayload(new JacksonSerDes().serialize(value))
                            .build())
                    .build();
            var operations = new ArrayList<>(List.of(root));
            operations.addAll(storage.getAllOperations());
            return new DurableExecutionInput(
                    arn,
                    "token-" + attempt,
                    CheckpointUpdatedExecutionState.builder()
                            .operations(operations)
                            .build(),
                    storage.getUpdatedOperationIdsSinceLastInvocation());
        }

        private Context context() {
            var request = value.marker() + "-" + (++attempt);
            var logger = new LambdaLogger() {
                public void log(String message) {}

                public void log(byte[] message) {}
            };
            return (Context) Proxy.newProxyInstance(
                    Context.class.getClassLoader(),
                    new Class<?>[] {Context.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getAwsRequestId" -> request;
                        case "getRemainingTimeInMillis" -> 60000;
                        case "getXrayTraceId" -> {
                            headerThreads.add(Thread.currentThread().getName());
                            if ("throw".equals(header)) throw new IllegalStateException("controlled accessor failure");
                            yield header;
                        }
                        case "getLogger" -> logger;
                        case "getMemoryLimitInMB" -> 2048;
                        default -> null;
                    });
        }
    }
}
