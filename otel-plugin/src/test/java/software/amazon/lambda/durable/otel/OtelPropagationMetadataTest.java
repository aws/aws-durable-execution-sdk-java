// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;
import software.amazon.lambda.durable.plugin.PluginRunner;
import software.amazon.lambda.durable.plugin.PropagationInput;

class OtelPropagationMetadataTest {
    private static final String TRACE = "6955b900123456789012345678901234";
    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:parent/durable-execution/test/id";
    private static final Instant START = Instant.parse("2026-10-02T00:00:00Z");
    private static final PropagationInput INPUT =
            new PropagationInput(ARN, "invoke-op", "parent-context", "child:live");
    private static final AttributeKey<String> SERVICE = AttributeKey.stringKey("service.name");

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void collectorAndProducerMatchTheActualOperationAndSampling(boolean executionView, boolean sampled) {
        var fixture = fixture(executionView, sampled, new AtomicBoolean());
        var runner = fixture.runner();
        assertNull(runner.providePropagationMetadata(INPUT));
        var ambient = Span.wrap(SpanContext.create(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbb",
                TraceFlags.getSampled(),
                TraceState.getDefault()));
        try (var ignored = ambient.makeCurrent()) {
            start(runner, ARN, true);
            var planned = runner.providePropagationMetadata(INPUT).xAmznTraceId();
            assertTrue(fixture.exporter().getFinishedSpanItems().isEmpty(), "Metadata collection creates no span");
            runner.onOperationStart(operation(false));
            assertEquals(planned, runner.providePropagationMetadata(INPUT).xAmznTraceId());
            runner.onOperationEnd(operationEnd());
            end(runner, ARN, true, InvocationStatus.SUCCEEDED);
            assertHeader(
                    planned,
                    sampled,
                    new DeterministicIdGenerator().generateSpanIdForOperation(ARN, INPUT.operationId()));
            assertExportedOperation(fixture, planned, sampled);
        }
        assertNull(runner.providePropagationMetadata(INPUT), "Inactive invocation has no contribution");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void observedContinuationUsesItsActualSpanContext(boolean executionView) {
        var fixture = fixture(executionView, true, new AtomicBoolean());
        start(fixture.runner(), ARN, false);
        fixture.runner().onOperationStart(operation(true));
        var header = fixture.runner().providePropagationMetadata(INPUT).xAmznTraceId();
        fixture.runner().onOperationEnd(operationEnd());
        end(fixture.runner(), ARN, false, InvocationStatus.SUCCEEDED);
        assertExportedOperation(fixture, header, true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void resumeIdentityAndExecutionOwnershipRemainStable(boolean executionView) {
        var first = fixture(executionView, true, new AtomicBoolean());
        start(first.runner(), ARN, true);
        var initial = first.runner().providePropagationMetadata(INPUT).xAmznTraceId();
        end(first.runner(), ARN, true, InvocationStatus.PENDING);
        var resumed = fixture(executionView, true, new AtomicBoolean());
        start(resumed.runner(), ARN, false);
        assertEquals(initial, resumed.runner().providePropagationMetadata(INPUT).xAmznTraceId());
        assertNull(
                resumed.runner().providePropagationMetadata(new PropagationInput("other-arn", "op", null, "target")));
        end(resumed.runner(), ARN, false, InvocationStatus.SUCCEEDED);
        start(resumed.runner(), "other-arn", true);
        assertNull(resumed.runner().providePropagationMetadata(INPUT));
        end(resumed.runner(), "other-arn", true, InvocationStatus.SUCCEEDED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedSetupCannotExposePreviousExecutionContext(boolean executionView) {
        var fail = new AtomicBoolean();
        var fixture = fixture(executionView, true, fail);
        start(fixture.runner(), ARN, true);
        assertNotNull(fixture.runner().providePropagationMetadata(INPUT));
        end(fixture.runner(), ARN, true, InvocationStatus.SUCCEEDED);
        fail.set(true);
        start(fixture.runner(), "other-arn", true); // The runner contains the extractor's ordinary failure.
        assertNull(fixture.runner().providePropagationMetadata(INPUT));
        assertNull(
                fixture.runner().providePropagationMetadata(new PropagationInput("other-arn", "op", null, "target")));
        end(fixture.runner(), "other-arn", true, InvocationStatus.FAILED);
    }

    private static void assertHeader(String header, boolean sampled, String parentId) {
        assertEquals(
                "Root=1-6955b900-123456789012345678901234;Parent=" + parentId + ";Sampled=" + (sampled ? "1" : "0"),
                header);
    }

    private static void assertExportedOperation(Fixture fixture, String header, boolean sampled) {
        var spans = fixture.exporter().getFinishedSpanItems();
        if (!sampled) {
            assertTrue(spans.isEmpty());
            return;
        }
        assertEquals(3, spans.size(), "Only the normal operation, Invocation and Workflow spans are exported");
        var operation = spans.stream()
                .filter(span -> span.getName().equals("invoke"))
                .findFirst()
                .orElseThrow();
        assertHeader(header, true, operation.getSpanId());
        assertEquals(TRACE, operation.getTraceId());
        assertTrue(spans.stream()
                .allMatch(span -> "configured-service".equals(span.getResource().getAttribute(SERVICE))));
    }

    private static Fixture fixture(boolean executionView, boolean sampled, AtomicBoolean fail) {
        var exporter = InMemorySpanExporter.create();
        var builder = SdkTracerProvider.builder()
                .setResource(Resource.create(Attributes.of(SERVICE, "configured-service")))
                .setSampler(sampled ? Sampler.alwaysOff() : Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var config = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(() -> {
                    if (fail.get()) throw new IllegalStateException("extractor failed");
                    return new ExtractedContext(
                            TRACE,
                            "1234567890123456",
                            sampled ? ExtractedContext.Sampling.SAMPLED : ExtractedContext.Sampling.NOT_SAMPLED);
                })
                .build();
        DurableExecutionPlugin plugin =
                executionView ? new ExecutionOtelPlugin(builder, config) : new InvocationOtelPlugin(builder, config);
        return new Fixture(new PluginRunner(List.of(new DurableExecutionPlugin() {}, plugin)), exporter);
    }

    private static OperationInfo operation(boolean replay) {
        return new OperationInfo(
                INPUT.operationId(),
                "invoke",
                "CHAINED_INVOKE",
                null,
                INPUT.parentOperationId(),
                START,
                null,
                "STARTED",
                replay);
    }

    private static OperationEndInfo operationEnd() {
        return new OperationEndInfo(
                INPUT.operationId(),
                "invoke",
                "CHAINED_INVOKE",
                null,
                INPUT.parentOperationId(),
                START,
                START.plusSeconds(1),
                "SUCCEEDED",
                null,
                false,
                null);
    }

    private static void start(PluginRunner runner, String arn, boolean first) {
        runner.onInvocationStart(new InvocationInfo("request", arn, first, START));
    }

    private static void end(PluginRunner runner, String arn, boolean first, InvocationStatus status) {
        runner.onInvocationEnd(new InvocationEndInfo("request", arn, first, status, null));
    }

    private record Fixture(PluginRunner runner, InMemorySpanExporter exporter) {}
}
