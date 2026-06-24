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
    void clear_removesAllMdcKeys() {
        MDC.put(MdcSpanEnricher.MDC_TRACE_ID, "abc123");
        MDC.put(MdcSpanEnricher.MDC_SPAN_ID, "def456");
        MDC.put(MdcSpanEnricher.MDC_EXECUTION_ARN, "arn:test");

        MdcSpanEnricher.clear();

        assertNull(MDC.get(MdcSpanEnricher.MDC_TRACE_ID));
        assertNull(MDC.get(MdcSpanEnricher.MDC_SPAN_ID));
        assertNull(MDC.get(MdcSpanEnricher.MDC_EXECUTION_ARN));
    }

    @Test
    void inject_withNoActiveSpan_doesNotSetTraceIds() {
        MdcSpanEnricher.inject("arn:test");

        // No active span → no trace/span IDs, but ARN is still set
        assertNull(MDC.get(MdcSpanEnricher.MDC_TRACE_ID));
        assertNull(MDC.get(MdcSpanEnricher.MDC_SPAN_ID));
        assertEquals("arn:test", MDC.get(MdcSpanEnricher.MDC_EXECUTION_ARN));
    }

    @Test
    void inject_withNullArn_doesNotSetArn() {
        MdcSpanEnricher.inject(null);

        assertNull(MDC.get(MdcSpanEnricher.MDC_EXECUTION_ARN));
    }

    @Test
    void plugin_withMdcEnabled_setsArnInMdc() {
        // Test MDC through the full plugin lifecycle (where makeCurrent is called on same thread)
        var spanExporter = InMemorySpanExporter.create();

        var plugin = new OtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> null,
                true);

        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec-mdc-test", true));

        // Simulate onUserFunctionStart on this thread (same as production — hooks fire on user code thread)
        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "step", "STEP", "Step", null, Instant.now(), false, 1));

        // MDC should have execution ARN after onUserFunctionStart
        assertEquals("arn:exec-mdc-test", MDC.get(MdcSpanEnricher.MDC_EXECUTION_ARN));

        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "step", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));

        // MDC should be cleared after onUserFunctionEnd
        assertNull(MDC.get(MdcSpanEnricher.MDC_EXECUTION_ARN));
        assertNull(MDC.get(MdcSpanEnricher.MDC_TRACE_ID));

        plugin.onInvocationEnd(
                new InvocationEndInfo("req-1", "arn:exec-mdc-test", true, InvocationStatus.SUCCEEDED, null));
    }
}
