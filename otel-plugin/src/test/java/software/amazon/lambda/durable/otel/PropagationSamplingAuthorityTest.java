// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;
import software.amazon.lambda.durable.plugin.PluginRunner;
import software.amazon.lambda.durable.plugin.PropagationInput;

class PropagationSamplingAuthorityTest {
    @ParameterizedTest
    @CsvSource({
        "true,false,true", "true,false,false", "true,true,true", "true,true,false",
        "false,false,true", "false,false,false", "false,true,true", "false,true,false"
    })
    void replacementSamplerMayChooseDifferentInvocationAndInvokeFlags(
            boolean executionView, boolean opaque, boolean sampleInvoke) {
        var decisions = new ArrayList<String>();
        var replacement = new Sampler() {
            public SamplingResult shouldSample(
                    Context parent,
                    String traceId,
                    String name,
                    SpanKind kind,
                    Attributes attributes,
                    List<LinkData> links) {
                boolean sampled = name.equals("Invocation") ? !sampleInvoke : sampleInvoke;
                decisions.add(name + ":" + sampled);
                return sampled ? SamplingResult.recordAndSample() : SamplingResult.drop();
            }

            public String getDescription() {
                return "different-invocation-and-invoke-decisions";
            }
        };
        GlobalOpenTelemetry.resetForTest();
        OtelPluginAutoConfigurationState.markInstalled();
        try (var exporter = InMemorySpanExporter.create();
                var provider = SdkTracerProvider.builder()
                        .setSampler(replacement)
                        .setIdGenerator(new DeterministicIdGenerator())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build()) {
            var facade = new TracerProvider() {
                public Tracer get(String name) {
                    return provider.get(name);
                }

                public Tracer get(String name, String version) {
                    return provider.get(name, version);
                }
            };
            GlobalOpenTelemetry.set(new OpenTelemetry() {
                public TracerProvider getTracerProvider() {
                    return opaque ? facade : provider;
                }

                public ContextPropagators getPropagators() {
                    return ContextPropagators.noop();
                }
            });
            var config = OtelPluginConfig.builder()
                    .contextExtractor(() -> null)
                    .enableMdc(false)
                    .build();
            DurableExecutionPlugin plugin =
                    executionView ? new ExecutionOtelPlugin(config) : new InvocationOtelPlugin(config);
            var runner = new PluginRunner(List.of(plugin));
            var start = Instant.parse("2026-10-14T00:00:00Z");
            var arn = "arn:aws:lambda:us-east-1:123456789012:function:parent/durable-execution/test/id";
            var input = new PropagationInput(arn, "invoke-id", null, "target:live");
            runner.onInvocationStart(new InvocationInfo("request", arn, true, start));
            var planned = runner.providePropagationMetadata(input);
            runner.onOperationStart(new OperationInfo(
                    "invoke-id", "invoke", "CHAINED_INVOKE", "ChainedInvoke", null, start, null, "STARTED", false));
            var contribution = runner.providePropagationMetadata(input);
            runner.onOperationEnd(new OperationEndInfo(
                    "invoke-id",
                    "invoke",
                    "CHAINED_INVOKE",
                    "ChainedInvoke",
                    null,
                    start,
                    start.plusSeconds(1),
                    "SUCCEEDED",
                    null,
                    false,
                    null));
            runner.onInvocationEnd(new InvocationEndInfo("request", arn, true, InvocationStatus.SUCCEEDED, null));

            assertTrue(decisions.contains("Invocation:" + !sampleInvoke));
            assertTrue(decisions.contains("invoke:" + sampleInvoke));
            var invokes = exporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("invoke"))
                    .toList();
            assertEquals(sampleInvoke ? 1 : 0, invokes.size());
            assertNull(planned, "A planned operation has no authoritative per-span decision");
            if (executionView) {
                assertNull(contribution, "A deferred placeholder cannot determine this operation's sampler decision");
            } else {
                assertNotNull(contribution, "The invocation view has an actual operation SpanContext");
                assertTrue(contribution.xAmznTraceId().endsWith(";Sampled=" + (sampleInvoke ? 1 : 0)));
                if (sampleInvoke) {
                    assertTrue(contribution
                            .xAmznTraceId()
                            .contains(";Parent=" + invokes.get(0).getSpanId() + ";"));
                }
            }
        } finally {
            GlobalOpenTelemetry.resetForTest();
            OtelPluginAutoConfigurationState.resetInstalledForTest();
            DurableSamplingDecision.clearSharedStateForTest();
        }
    }
}
