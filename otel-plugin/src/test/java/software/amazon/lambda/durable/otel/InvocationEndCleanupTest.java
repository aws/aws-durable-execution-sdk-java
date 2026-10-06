// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class InvocationEndCleanupTest {
    @SuppressWarnings("removal")
    static Stream<Arguments> fatalCases() {
        return Stream.of(false, true)
                .flatMap(executionView -> Stream.of(false, true)
                        .flatMap(wrapped -> Stream.of(new InternalError("first end hook"), new ThreadDeath())
                                .map(fatal -> Arguments.of(executionView, wrapped, fatal))));
    }

    @ParameterizedTest
    @MethodSource("fatalCases")
    void laterOtelAndObserverFinalizeBeforeEndFatalEscapes(boolean executionView, boolean wrapped, Error fatal) {
        var exporter = InMemorySpanExporter.create();
        var builder = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var config = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(() -> new ExtractedContext(
                        "12345678901234567890123456789012", "1234567890123456", ExtractedContext.Sampling.SAMPLED))
                .build();
        var otel = executionView
                ? ExecutionOtelPlugin.factory(builder, config)
                : InvocationOtelPlugin.factory(builder, config);
        var calls = new ArrayList<String>();
        var snapshots = new ArrayList<InvocationEndInfo>();
        DurableExecutionPluginFactory first = info -> new DurableExecutionPlugin() {
            public void onInvocationEnd(InvocationEndInfo end) {
                calls.add("first");
                snapshots.add(end);
                if (wrapped) throw new CompletionException(new ExecutionException(fatal));
                throw fatal;
            }
        };
        DurableExecutionPluginFactory last = info -> new DurableExecutionPlugin() {
            public void onInvocationEnd(InvocationEndInfo end) {
                calls.add("last");
                snapshots.add(end);
            }
        };
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> "done",
                DurableConfig.builder().withPlugins(first, otel, last).build());
        assertSame(fatal, assertThrows(Error.class, () -> runner.run("input")));
        assertEquals(List.of("first", "last"), calls);
        assertSame(snapshots.get(0), snapshots.get(1), "each plugin receives the same single dispatch snapshot");
        assertEquals(InvocationStatus.SUCCEEDED, snapshots.get(1).invocationStatus());
        assertTrue(
                exporter.getFinishedSpanItems().stream()
                        .anyMatch(span -> span.getName().equals("Invocation")),
                "the later OTel plugin must finish and export its span before the fatal reaches the caller");
    }
}
