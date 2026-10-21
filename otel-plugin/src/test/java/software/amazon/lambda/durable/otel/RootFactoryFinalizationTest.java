// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

@Timeout(15)
class RootFactoryFinalizationTest {
    @ParameterizedTest
    @CsvSource({"false,none", "true,none", "false,root", "true,root", "false,invocation", "true,invocation"})
    void freshPublicFactoryInstancesEndAnchorBeforeBodyAndFlushOnceEvenAfterOrdinaryEndFailure(
            boolean executionView, String failurePhase) {
        var roots = new AtomicInteger();
        var flushes = new AtomicInteger();
        var failOnce = new AtomicBoolean(!failurePhase.equals("none"));
        var effects = new AtomicInteger();
        var bodies = new AtomicInteger();
        var instances = new CopyOnWriteArrayList<DurableExecutionPlugin>();
        try (var exporter = InMemorySpanExporter.create()) {
            var observer = new SpanProcessor() {
                public void onStart(Context parent, ReadWriteSpan span) {}

                public boolean isStartRequired() {
                    return false;
                }

                public void onEnd(ReadableSpan span) {
                    if (span.getName().equals("DurableExecutionRoot")) roots.incrementAndGet();
                    if (span.getName().equals(failurePhase.equals("root") ? "DurableExecutionRoot" : "Invocation")
                            && failOnce.compareAndSet(true, false))
                        throw new IllegalStateException("ordinary processor failure");
                }

                public boolean isEndRequired() {
                    return true;
                }

                public CompletableResultCode forceFlush() {
                    flushes.incrementAndGet();
                    return CompletableResultCode.ofSuccess();
                }
            };
            var builder = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .addSpanProcessor(observer);
            var settings = OtelPluginConfig.builder()
                    .enableMdc(false)
                    .contextExtractor(() -> null)
                    .build();
            var actual = executionView
                    ? ExecutionOtelPlugin.factory(builder, settings)
                    : InvocationOtelPlugin.factory(builder, settings);
            DurableExecutionPluginFactory factory = info -> {
                var instance = actual.createPlugin(info);
                instances.add(instance);
                return instance;
            };
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, context) -> {
                        assertEquals(
                                bodies.incrementAndGet(),
                                roots.get(),
                                "Anchor ends before customer code on each invocation");
                        context.step("saved", String.class, step -> {
                            effects.incrementAndGet();
                            return "saved";
                        });
                        context.wait("pause", Duration.ofSeconds(1));
                        return "done";
                    },
                    DurableConfig.builder().withPlugins(factory).build());
            assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
            assertEquals(1, flushes.get());
            runner.advanceTime();
            assertEquals(
                    ExecutionStatus.SUCCEEDED, runner.runUntilComplete("input").getStatus());
            assertEquals(2, flushes.get());
            assertEquals(2, instances.size());
            assertNotSame(instances.get(0), instances.get(1));
            assertEquals(1, effects.get());
            var anchors = exporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("DurableExecutionRoot"))
                    .toList();
            assertEquals(2, anchors.size());
            assertEquals(anchors.get(0).getSpanContext(), anchors.get(1).getSpanContext());
            assertTrue(anchors.stream().allMatch(span -> span.getStartEpochNanos() == span.getEndEpochNanos()));
            instances.forEach(plugin -> plugin.onInvocationEnd(
                    new InvocationEndInfo("already-ended", "unused", false, InvocationStatus.SUCCEEDED, null)));
            assertEquals(2, flushes.get());
            assertEquals(2, roots.get());
        }
    }
}
