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

        var plugin = new OtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spanExporter)),
                () -> null,
                true);

        plugin.onInvocationStart(new InvocationInfo("req-1", "arn:exec-mdc-test", true));

        plugin.onUserFunctionStart(
                new UserFunctionStartInfo("op-1", "step", "STEP", "Step", null, Instant.now(), false, 1));

        // MDC should have trace fields after onUserFunctionStart
        assertNotNull(MDC.get(MdcSpanEnricher.MDC_TRACE_ID));
        assertNotNull(MDC.get(MdcSpanEnricher.MDC_SPAN_ID));
        assertNotNull(MDC.get(MdcSpanEnricher.MDC_TRACE_SAMPLED));

        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                "op-1", "step", "STEP", "Step", null, Instant.now(), Instant.now(), false, 1, true, null));

        // MDC should be cleared after onUserFunctionEnd
        assertNull(MDC.get(MdcSpanEnricher.MDC_TRACE_ID));
        assertNull(MDC.get(MdcSpanEnricher.MDC_SPAN_ID));
        assertNull(MDC.get(MdcSpanEnricher.MDC_TRACE_SAMPLED));

        plugin.onInvocationEnd(
                new InvocationEndInfo("req-1", "arn:exec-mdc-test", true, InvocationStatus.SUCCEEDED, null));
    }
}
