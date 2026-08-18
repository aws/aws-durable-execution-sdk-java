// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeterministicIdGeneratorTest {

    private static final Instant EXECUTION_START_TIME = Instant.parse("2026-08-15T00:00:00Z");

    private DeterministicIdGenerator generator;

    @BeforeEach
    void setUp() {
        DeterministicIdGenerator.clearSharedStateForTest();
        generator = new DeterministicIdGenerator();
    }

    @AfterEach
    void tearDown() {
        DeterministicIdGenerator.clearSharedStateForTest();
    }

    @Test
    void generateTraceId_withoutArn_returnsRandom() {
        var id1 = generator.generateTraceId();
        var id2 = generator.generateTraceId();

        assertNotNull(id1);
        assertEquals(32, id1.length());
        // Random IDs should differ (extremely unlikely to collide)
        assertNotEquals(id1, id2);
    }

    @Test
    void scopedIds_delegateOutsideScope() {
        var providerGenerator = new DeterministicIdGenerator();
        try (var provider =
                SdkTracerProvider.builder().setIdGenerator(providerGenerator).build()) {
            var pluginTracer = provider.get("durable-plugin");
            var unrelatedTracer = provider.get("unrelated-library");

            var before = unrelatedTracer.spanBuilder("before").setNoParent().startSpan();
            var workflowTraceId = generator.generateTraceIdForExecution("arn:exec1", EXECUTION_START_TIME);
            var workflowSpanId = generator.generateWorkflowSpanId("arn:exec1");
            var workflow = generator.startSpan(
                    pluginTracer.spanBuilder("Workflow").setNoParent(), workflowTraceId, workflowSpanId);
            var during = unrelatedTracer.spanBuilder("during").setNoParent().startSpan();
            var after = unrelatedTracer.spanBuilder("after").setNoParent().startSpan();

            assertEquals(workflowTraceId, workflow.getSpanContext().getTraceId());
            assertEquals(workflowSpanId, workflow.getSpanContext().getSpanId());
            assertAllFreshRoots(
                    workflow.getSpanContext(),
                    before.getSpanContext(),
                    during.getSpanContext(),
                    after.getSpanContext());

            before.end();
            workflow.end();
            during.end();
            after.end();
        }
    }

    @Test
    void scopedIds_bridgeAcrossGeneratorInstances() {
        var agentGenerator = new DeterministicIdGenerator();
        try (var provider =
                SdkTracerProvider.builder().setIdGenerator(agentGenerator).build()) {
            var workflowTraceId = generator.generateTraceIdForExecution("arn:exec1", EXECUTION_START_TIME);
            var workflowSpanId = generator.generateWorkflowSpanId("arn:exec1");
            var workflow = generator.startSpan(
                    provider.get("durable-plugin").spanBuilder("Workflow").setNoParent(),
                    workflowTraceId,
                    workflowSpanId);

            assertEquals(workflowTraceId, workflow.getSpanContext().getTraceId());
            assertEquals(workflowSpanId, workflow.getSpanContext().getSpanId());
            assertNotEquals(workflowTraceId, agentGenerator.generateTraceId());
            workflow.end();
        }
    }

    @Test
    void scopedTraceId_isConsumedBeforeSamplerStartsNestedRoot() {
        var fallbackTraceId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        var fallbackSpanId = "cccccccccccccccc";
        var agentGenerator = new DeterministicIdGenerator(fixedIds(fallbackTraceId, fallbackSpanId));
        var tracerReference = new AtomicReference<Tracer>();
        var nestedSpanContext = new AtomicReference<SpanContext>();
        var sampler = nestedRootSampler(tracerReference, nestedSpanContext);
        try (var provider = SdkTracerProvider.builder()
                .setIdGenerator(agentGenerator)
                .setSampler(sampler)
                .build()) {
            var tracer = provider.get("durable-plugin");
            tracerReference.set(tracer);
            var workflowTraceId = generator.generateTraceIdForExecution("arn:exec1", EXECUTION_START_TIME);
            var workflowSpanId = generator.generateWorkflowSpanId("arn:exec1");

            var workflow =
                    generator.startSpan(tracer.spanBuilder("Workflow").setNoParent(), workflowTraceId, workflowSpanId);

            assertEquals(workflowTraceId, workflow.getSpanContext().getTraceId());
            assertEquals(workflowSpanId, workflow.getSpanContext().getSpanId());
            assertNotNull(nestedSpanContext.get());
            assertEquals(fallbackTraceId, nestedSpanContext.get().getTraceId());
            assertEquals(fallbackSpanId, nestedSpanContext.get().getSpanId());
            workflow.end();
        }
    }

    @Test
    void concurrentScopedIds_doNotOverwriteEachOther() throws Exception {
        var agentGenerator = new DeterministicIdGenerator();
        var executor = Executors.newFixedThreadPool(2);
        try (var provider =
                SdkTracerProvider.builder().setIdGenerator(agentGenerator).build()) {
            var tracer = provider.get("durable-plugin");
            var barrier = new CyclicBarrier(2);
            var traceId1 = generator.generateTraceIdForExecution("arn:exec1", EXECUTION_START_TIME);
            var traceId2 = generator.generateTraceIdForExecution("arn:exec2", EXECUTION_START_TIME);
            var spanId1 = generator.generateWorkflowSpanId("arn:exec1");
            var spanId2 = generator.generateWorkflowSpanId("arn:exec2");

            var first = executor.submit(() -> {
                barrier.await();
                return scopedSpanContext(tracer, new DeterministicIdGenerator(), traceId1, spanId1);
            });
            var second = executor.submit(() -> {
                barrier.await();
                return scopedSpanContext(tracer, new DeterministicIdGenerator(), traceId2, spanId2);
            });

            assertEquals(traceId1, first.get().getTraceId());
            assertEquals(spanId1, first.get().getSpanId());
            assertEquals(traceId2, second.get().getTraceId());
            assertEquals(spanId2, second.get().getSpanId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void installOn_preservesConfiguredFallbackGenerator() {
        var fallbackTraceId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        var fallbackSpanId = "cccccccccccccccc";
        var builder = SdkTracerProvider.builder().setIdGenerator(fixedIds(fallbackTraceId, fallbackSpanId));
        var installedGenerator = DeterministicIdGenerator.installOn(builder);

        try (var provider = builder.build()) {
            var unrelated =
                    provider.get("unrelated").spanBuilder("root").setNoParent().startSpan();
            var workflowTraceId = generator.generateTraceIdForExecution("arn:exec1", EXECUTION_START_TIME);
            var workflowSpanId = generator.generateWorkflowSpanId("arn:exec1");
            var workflow = generator.startSpan(
                    provider.get("durable").spanBuilder("Workflow").setNoParent(), workflowTraceId, workflowSpanId);

            assertEquals(fallbackTraceId, unrelated.getSpanContext().getTraceId());
            assertEquals(fallbackSpanId, unrelated.getSpanContext().getSpanId());
            assertEquals(workflowTraceId, workflow.getSpanContext().getTraceId());
            assertEquals(workflowSpanId, workflow.getSpanContext().getSpanId());
            assertSame(installedGenerator, DeterministicIdGenerator.installOn(builder));

            unrelated.end();
            workflow.end();
        }
    }

    @Test
    void executionTraceId_usesStartTimestampAndArn() {
        var traceId = generator.generateTraceIdForExecution("arn:exec1", EXECUTION_START_TIME);

        assertEquals("6a7fac00", traceId.substring(0, 8));
        assertEquals(traceId, generator.generateTraceIdForExecution("arn:exec1", EXECUTION_START_TIME));
        assertNotEquals(traceId, generator.generateTraceIdForExecution("arn:exec2", EXECUTION_START_TIME));
    }

    @Test
    void generateTraceId_withArn_returnsDeterministic() {
        generator.setDurableExecutionArn("arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1");

        var id1 = generator.generateTraceId();
        var id2 = generator.generateTraceId();

        assertEquals(32, id1.length());
        assertEquals(id1, id2, "Same ARN should always produce same trace ID");
    }

    @Test
    void generateTraceId_differentArns_produceDifferentIds() {
        generator.setDurableExecutionArn("arn:exec1");
        var id1 = generator.generateTraceId();

        generator.setDurableExecutionArn("arn:exec2");
        var id2 = generator.generateTraceId();

        assertNotEquals(id1, id2);
    }

    @Test
    void generateSpanId_withoutOperationId_returnsRandom() {
        var id1 = generator.generateSpanId();
        var id2 = generator.generateSpanId();

        assertEquals(16, id1.length());
        assertNotEquals(id1, id2);
    }

    @Test
    void generateSpanId_withOperationId_returnsDeterministic() {
        generator.setDurableExecutionArn("arn:exec1");
        generator.setNextSpanOperationId("op-hash-1");
        var id1 = generator.generateSpanId();

        generator.setNextSpanOperationId("op-hash-1");
        var id2 = generator.generateSpanId();

        assertEquals(16, id1.length());
        assertEquals(id1, id2, "Same operation ID should produce same span ID");
    }

    @Test
    void generateSpanId_differentOperationIds_produceDifferentIds() {
        generator.setDurableExecutionArn("arn:exec1");

        generator.setNextSpanOperationId("op-1");
        var id1 = generator.generateSpanId();

        generator.setNextSpanOperationId("op-2");
        var id2 = generator.generateSpanId();

        assertNotEquals(id1, id2);
    }

    @Test
    void generateSpanId_consumesPendingId() {
        generator.setNextSpanOperationId("op-1");
        var deterministic = generator.generateSpanId();

        // Second call should be random (pending was consumed)
        var random = generator.generateSpanId();
        assertNotEquals(deterministic, random);
    }

    @Test
    void generateSpanIdForOperation_doesNotConsumePending() {
        generator.setDurableExecutionArn("arn:exec1");
        generator.setNextSpanOperationId("op-1");

        // This should NOT consume the pending
        var forOperation = generator.generateSpanIdForOperation("op-2");

        // The pending should still be consumed by generateSpanId
        var fromPending = generator.generateSpanId();

        assertEquals(16, forOperation.length());
        assertEquals(16, fromPending.length());
        assertNotEquals(forOperation, fromPending);
    }

    @Test
    void generateSpanIdForOperation_isDeterministic() {
        generator.setDurableExecutionArn("arn:exec1");

        var id1 = generator.generateSpanIdForOperation("op-1");
        var id2 = generator.generateSpanIdForOperation("op-1");

        assertEquals(id1, id2);
    }

    @Test
    void persistentIds_areIsolatedAcrossGeneratorInstances() {
        var pluginGenerator = new DeterministicIdGenerator();
        var fallbackTraceId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        var fallbackSpanId = "cccccccccccccccc";
        var agentGenerator = new DeterministicIdGenerator(fixedIds(fallbackTraceId, fallbackSpanId));

        pluginGenerator.setDurableExecutionArn("arn:exec1");
        pluginGenerator.setNextSpanOperationId("op-1");

        assertEquals(fallbackTraceId, agentGenerator.generateTraceId());
        assertEquals(fallbackSpanId, agentGenerator.generateSpanId());
    }

    @Test
    void traceId_isValidHex() {
        generator.setDurableExecutionArn("arn:exec1");
        var traceId = generator.generateTraceId();

        assertTrue(traceId.matches("[0-9a-f]{32}"), "Trace ID should be 32 hex chars: " + traceId);
    }

    @Test
    void spanId_isValidHex() {
        generator.setDurableExecutionArn("arn:exec1");
        generator.setNextSpanOperationId("op-1");
        var spanId = generator.generateSpanId();

        assertTrue(spanId.matches("[0-9a-f]{16}"), "Span ID should be 16 hex chars: " + spanId);
    }

    // ─── X-Ray extracted trace ID priority tests ─────────────────────────

    @Test
    void generateTraceId_extractedTakesPriorityOverArn() {
        var xrayTraceId = "5759e988bd862e3fe1be46a994272793";
        generator.setDurableExecutionArn("arn:aws:lambda:us-east-1:123:function:test");
        generator.setExtractedTraceId(xrayTraceId);

        var result = generator.generateTraceId();

        assertEquals(xrayTraceId, result, "Extracted X-Ray trace ID should take priority over ARN-derived");
    }

    @Test
    void generateTraceId_extractedIdReturnedConsistently() {
        var xrayTraceId = "aabbccddee112233445566778899aabb";
        generator.setExtractedTraceId(xrayTraceId);

        // Should return the same value on every call
        assertEquals(xrayTraceId, generator.generateTraceId());
        assertEquals(xrayTraceId, generator.generateTraceId());
        assertEquals(xrayTraceId, generator.generateTraceId());
    }

    @Test
    void generateTraceId_extractedIdOverridesArnDerived() {
        generator.setDurableExecutionArn("arn:exec1");
        var arnDerived = generator.generateTraceId();

        // Now set an extracted ID — it should override
        var xrayTraceId = "1234567890abcdef1234567890abcdef";
        generator.setExtractedTraceId(xrayTraceId);

        var afterExtracted = generator.generateTraceId();
        assertEquals(xrayTraceId, afterExtracted);
        assertNotEquals(arnDerived, afterExtracted, "Extracted should differ from ARN-derived");
    }

    @Test
    void generateTraceId_priorityOrder_extractedFirst() {
        // Priority 1: extracted > Priority 2: ARN-derived > Priority 3: random
        var extracted = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1";
        generator.setDurableExecutionArn("arn:some-arn");
        generator.setExtractedTraceId(extracted);

        assertEquals(extracted, generator.generateTraceId());
    }

    @Test
    void generateTraceId_priorityOrder_arnDerivedWhenNoExtracted() {
        // Priority 2: ARN-derived when no extracted set
        generator.setDurableExecutionArn("arn:some-arn");

        var result = generator.generateTraceId();
        assertNotNull(result);
        assertEquals(32, result.length());

        // Should be deterministic
        assertEquals(result, generator.generateTraceId());
    }

    @Test
    void generateTraceId_priorityOrder_randomWhenNeitherSet() {
        // Priority 3: random when neither extracted nor ARN set
        var id1 = generator.generateTraceId();
        var id2 = generator.generateTraceId();

        assertNotNull(id1);
        assertEquals(32, id1.length());
        assertNotEquals(id1, id2, "Random IDs should differ");
    }

    @Test
    void setExtractedTraceId_withNull_clearsExtracted() {
        var xrayTraceId = "5759e988bd862e3fe1be46a994272793";
        generator.setDurableExecutionArn("arn:exec1");
        generator.setExtractedTraceId(xrayTraceId);

        assertEquals(xrayTraceId, generator.generateTraceId());

        // Clear extracted — should fall back to ARN-derived
        generator.setExtractedTraceId(null);
        var afterClear = generator.generateTraceId();
        assertNotEquals(xrayTraceId, afterClear, "Should fall back to ARN-derived after clearing extracted");
        assertEquals(32, afterClear.length());
    }

    @Test
    void setExtractedTraceId_simulatesMultipleInvocations_sameExecution() {
        // Simulates backend propagating same X-Ray Root across invocations
        var xrayTraceId = "5759e988bd862e3fe1be46a994272793";

        // First invocation
        generator.setExtractedTraceId(xrayTraceId);
        generator.setDurableExecutionArn("arn:exec1");
        var firstInvocation = generator.generateTraceId();

        // Second invocation (same execution, same X-Ray Root from backend)
        var generator2 = new DeterministicIdGenerator();
        generator2.setExtractedTraceId(xrayTraceId);
        generator2.setDurableExecutionArn("arn:exec1");
        var secondInvocation = generator2.generateTraceId();

        assertEquals(firstInvocation, secondInvocation, "Same X-Ray Root should produce same trace across invocations");
        assertEquals(xrayTraceId, firstInvocation);
    }

    @Test
    void setExtractedTraceId_validXrayFormat_32HexChars() {
        // Real-world format: X-Ray Root stripped and dashless
        var xrayTraceId = "5759e988bd862e3fe1be46a994272793";
        generator.setExtractedTraceId(xrayTraceId);

        var result = generator.generateTraceId();
        assertEquals(xrayTraceId, result);
        assertTrue(result.matches("[0-9a-f]{32}"));
    }

    private static Sampler nestedRootSampler(
            AtomicReference<Tracer> tracerReference, AtomicReference<SpanContext> nestedSpanContext) {
        return new Sampler() {
            @Override
            public SamplingResult shouldSample(
                    Context parentContext,
                    String traceId,
                    String name,
                    SpanKind spanKind,
                    Attributes attributes,
                    List<LinkData> parentLinks) {
                if ("Workflow".equals(name)) {
                    var nested = tracerReference
                            .get()
                            .spanBuilder("nested-root")
                            .setNoParent()
                            .startSpan();
                    nestedSpanContext.set(nested.getSpanContext());
                    nested.end();
                }
                return SamplingResult.recordAndSample();
            }

            @Override
            public String getDescription() {
                return "NestedRootSampler";
            }
        };
    }

    private static SpanContext scopedSpanContext(
            io.opentelemetry.api.trace.Tracer tracer,
            DeterministicIdGenerator pluginGenerator,
            String traceId,
            String spanId) {
        var span = pluginGenerator.startSpan(tracer.spanBuilder("Workflow").setNoParent(), traceId, spanId);
        var spanContext = span.getSpanContext();
        span.end();
        return spanContext;
    }

    private static void assertAllFreshRoots(SpanContext workflow, SpanContext... unrelated) {
        for (var spanContext : unrelated) {
            assertNotEquals(workflow.getTraceId(), spanContext.getTraceId());
        }
        for (var left = 0; left < unrelated.length; left++) {
            for (var right = left + 1; right < unrelated.length; right++) {
                assertNotEquals(unrelated[left].getTraceId(), unrelated[right].getTraceId());
            }
        }
    }

    private static IdGenerator fixedIds(String traceId, String spanId) {
        return new IdGenerator() {
            @Override
            public String generateSpanId() {
                return spanId;
            }

            @Override
            public String generateTraceId() {
                return traceId;
            }
        };
    }
}
