// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.otel.Invocations.started;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import software.amazon.lambda.durable.plugin.*;

class MdcSpanEnricherTest {

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    void mdcKeyNames_matchJsAndPythonSchema() {
        // The JS (enrichLogContext) and Python (OtelContextLogFilter) plugins emit
        // traceId / spanId / otelTraceSampled. Java's MDC keys must match so all
        // three SDKs share one log-trace-correlation field schema.
        assertEquals("traceId", MdcSpanEnricher.MDC_TRACE_ID);
        assertEquals("spanId", MdcSpanEnricher.MDC_SPAN_ID);
        assertEquals("otelTraceSampled", MdcSpanEnricher.MDC_TRACE_SAMPLED);
    }

    @Test
    void clear_removesAllMdcKeys() {
        MDC.put(MdcSpanEnricher.MDC_TRACE_ID, "abc123");
        MDC.put(MdcSpanEnricher.MDC_SPAN_ID, "def456");
        MDC.put(MdcSpanEnricher.MDC_TRACE_SAMPLED, "true");

        MdcSpanEnricher.clear();

        assertNull(MDC.get(MdcSpanEnricher.MDC_TRACE_ID));
        assertNull(MDC.get(MdcSpanEnricher.MDC_SPAN_ID));
        assertNull(MDC.get(MdcSpanEnricher.MDC_TRACE_SAMPLED));
    }

    @Test
    void inject_withNoActiveSpan_doesNotSetMdcFields() {
        MdcSpanEnricher.inject();

        assertNull(MDC.get(MdcSpanEnricher.MDC_TRACE_ID));
        assertNull(MDC.get(MdcSpanEnricher.MDC_SPAN_ID));
        assertNull(MDC.get(MdcSpanEnricher.MDC_TRACE_SAMPLED));
    }

    @Test
    void plugin_withMdcEnabled_setsFieldsInMdc() {
        var spanExporter = InMemorySpanExporter.create();

        var pluginFactory = InvocationOtelPlugin.factory(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> null)
                        .enableMdc(true)
                        .build());

        var plugin = started(pluginFactory, new InvocationInfo("req-1", "arn:exec-mdc-test", true, Instant.now()));

        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "step", "STEP", "Step", null, Instant.now(), false, 1));

        // MDC should have trace fields after onUserFunctionStart
        assertNotNull(MDC.get(MdcSpanEnricher.MDC_TRACE_ID));
        assertNotNull(MDC.get(MdcSpanEnricher.MDC_SPAN_ID));
        assertNotNull(MDC.get(MdcSpanEnricher.MDC_TRACE_SAMPLED));

        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1",
                "step",
                "STEP",
                "Step",
                null,
                Instant.now(),
                Instant.now(),
                false,
                1,
                UserFunctionOutcome.SUCCEEDED,
                null));

        // After onUserFunctionEnd: span_id is cleared, but trace_id remains for handler-level logs between steps
        assertNotNull(MDC.get(MdcSpanEnricher.MDC_TRACE_ID), "trace_id should persist between steps");
        assertNull(MDC.get(MdcSpanEnricher.MDC_SPAN_ID), "span_id should be cleared after step");
        assertNotNull(MDC.get(MdcSpanEnricher.MDC_TRACE_SAMPLED), "trace_flags should persist between steps");

        plugin.onInvocationEnd(
                new InvocationEndInfo("req-1", "arn:exec-mdc-test", true, InvocationStatus.SUCCEEDED, null));

        // After onInvocationEnd: all MDC fields are cleared
        assertNull(MDC.get(MdcSpanEnricher.MDC_TRACE_ID));
        assertNull(MDC.get(MdcSpanEnricher.MDC_SPAN_ID));
        assertNull(MDC.get(MdcSpanEnricher.MDC_TRACE_SAMPLED));
    }

    @Test
    void logCorrelationFollowsEachInvocationsOwnInstance() {
        // Regression guard taken from the Python port of this refactor: there a log filter installed by the first
        // invocation's plugin outlived that plugin and kept querying the discarded instance, so log correlation
        // silently stopped after the first invocation. Java correlates through the SLF4J MDC, written by the hooks of
        // whichever instance is serving the invocation, so every invocation publishes its own execution trace. This
        // test pins that down across two invocations served by two instances of one factory.
        var spanExporter = InMemorySpanExporter.create();
        var pluginFactory = InvocationOtelPlugin.factory(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> null)
                        .enableMdc(true)
                        .build());

        var first = started(pluginFactory, new InvocationInfo("req-1", "arn:exec-a", true, Instant.now()));
        var firstTraceId = MDC.get(MdcSpanEnricher.MDC_TRACE_ID);
        first.onInvocationEnd(new InvocationEndInfo("req-1", "arn:exec-a", true, InvocationStatus.SUCCEEDED, null));
        assertNull(MDC.get(MdcSpanEnricher.MDC_TRACE_ID), "an invocation clears the correlation it set");

        var second = started(pluginFactory, new InvocationInfo("req-2", "arn:exec-b", true, Instant.now()));
        var secondTraceId = MDC.get(MdcSpanEnricher.MDC_TRACE_ID);
        second.onInvocationEnd(new InvocationEndInfo("req-2", "arn:exec-b", true, InvocationStatus.SUCCEEDED, null));

        assertNotNull(firstTraceId);
        assertNotNull(secondTraceId, "log correlation must not stop after the first invocation");
        assertNotEquals(firstTraceId, secondTraceId, "each instance publishes its own execution trace");
        var invocationTraceIds = spanExporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("Invocation"))
                .map(SpanData::getTraceId)
                .toList();
        assertEquals(
                List.of(firstTraceId, secondTraceId),
                invocationTraceIds,
                "the correlated trace ID is the one on that invocation's own Invocation span");
    }
}
