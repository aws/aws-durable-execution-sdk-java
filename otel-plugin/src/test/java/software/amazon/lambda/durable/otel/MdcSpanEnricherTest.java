// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
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

        var plugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> null)
                        .enableMdc(true)
                        .build());

        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec-mdc-test", true, Instant.now()));

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
    void asyncReturn_releasesThreadLocalScopeBeforeLogicalFunctionEnd() {
        var spanExporter = InMemorySpanExporter.create();
        var plugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> null)
                        .enableMdc(true)
                        .build());
        var startInfo = new UserFunctionStartInfo("op-1", "step", "STEP", "Step", null, Instant.now(), false, 1);

        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec-mdc-async", true, Instant.now()));
        plugin.onUserFunctionStart(startInfo);

        assertNotNull(MDC.get(MdcSpanEnricher.MDC_SPAN_ID));
        plugin.onUserFunctionAsyncReturn(startInfo);
        assertNull(MDC.get(MdcSpanEnricher.MDC_SPAN_ID));
        assertTrue(spanExporter.getFinishedSpanItems().stream()
                .noneMatch(span -> span.getName().equals("step attempt 1")));

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
        assertTrue(spanExporter.getFinishedSpanItems().stream()
                .anyMatch(span -> span.getName().equals("step attempt 1")));

        plugin.onInvocationEnd(
                new InvocationEndInfo("req-1", "arn:exec-mdc-async", true, InvocationStatus.SUCCEEDED, null));
    }

    @Test
    void asyncInvocationReturn_clearsMdcBeforeLogicalInvocationEnd() {
        var spanExporter = InMemorySpanExporter.create();
        var plugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                OtelPluginConfig.builder()
                        .contextExtractor(() -> null)
                        .enableMdc(true)
                        .build());
        var invocationInfo = new InvocationInfo("req-1", "arn:exec-async-handler", true, Instant.now());

        plugin.onInvocationStart(invocationInfo);
        assertNotNull(MDC.get(MdcSpanEnricher.MDC_TRACE_ID));

        plugin.onInvocationAsyncReturn(invocationInfo);
        assertNull(MDC.get(MdcSpanEnricher.MDC_TRACE_ID));
        assertTrue(spanExporter.getFinishedSpanItems().stream()
                .noneMatch(span -> span.getName().equals("Invocation")));

        plugin.onInvocationEnd(
                new InvocationEndInfo("req-1", "arn:exec-async-handler", true, InvocationStatus.SUCCEEDED, null));
        assertTrue(spanExporter.getFinishedSpanItems().stream()
                .anyMatch(span -> span.getName().equals("Invocation")));
    }
}
