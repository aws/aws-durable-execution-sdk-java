// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/** Provider sampling policy is independent of propagation flags and is stable across durable resumes and retries. */
class ConfiguredSamplerIntegrationTest {

    // TraceIdRatioBased compares the absolute lower 64 bits against its threshold. These are deterministic witnesses
    // on either side of the 0.5 threshold, rather than a probabilistic assertion about a small batch of random IDs.
    private static final String BELOW_THRESHOLD = "0123456789abcdef0000000000000001";
    private static final String ABOVE_THRESHOLD = "0123456789abcdef7fffffffffffffff";
    private static final String REMOTE_PARENT_ID = "1234567890abcdef";
    private static final String AMBIENT_PARENT_ID = "fedcba0987654321";

    @BeforeEach
    @AfterEach
    void clearSharedState() {
        GlobalOpenTelemetry.resetForTest();
        DeterministicIdGenerator.clearSharedStateForTest();
        DurableSamplingDecision.clearSharedStateForTest();
        OtelPluginAutoConfigurationState.resetInstalledForTest();
    }

    static Stream<Arguments> policies() {
        return Stream.of(false, true)
                .flatMap(executionView -> Stream.of(
                                "always_on",
                                "always_off",
                                "traceidratio",
                                "parentbased_always_on",
                                "parentbased_always_off",
                                "parentbased_traceidratio")
                        .flatMap(policy -> Stream.of("sampled", "not_sampled", "unset", "no_parent", "invalid_parent")
                                .flatMap(parent -> Stream.of(BELOW_THRESHOLD, ABOVE_THRESHOLD)
                                        .map(traceId -> Arguments.of(executionView, policy, parent, traceId)))));
    }

    @ParameterizedTest(name = "execution={0}, sampler={1}, parent={2}, trace={3}")
    @MethodSource("policies")
    void providerSamplerControlsEveryDurableSpan(boolean executionView, String policy, String parent, String traceId) {
        try (var exporter = InMemorySpanExporter.create()) {
            var config = config(executionView, sampler(policy), extracted(parent, traceId), exporter);
            var runner = LocalDurableTestRunner.create(
                    String.class, (input, ctx) -> ctx.step("work", String.class, stepCtx -> input), config);

            assertEquals(
                    ExecutionStatus.SUCCEEDED, runner.runUntilComplete("result").getStatus());

            var spans = exporter.getFinishedSpanItems();
            assertSelection(spans, expected(policy, parent, traceId), traceId);
            if (!spans.isEmpty()) {
                assertNames(spans, "Workflow", "Invocation", "work", "work attempt 1");
                if (parent.equals("no_parent") || parent.equals("invalid_parent")) {
                    assertTrue(
                            spans.stream()
                                    .anyMatch(
                                            span -> !span.getParentSpanContext().isValid()),
                            "A sampled execution without a usable propagated parent exports its synthetic root");
                }
            }
        }
    }

    static Stream<Arguments> resumedPolicies() {
        return Stream.of(false, true)
                .flatMap(executionView -> Stream.of("always_off", "traceidratio")
                        .flatMap(policy -> Stream.of("sampled", "not_sampled", "no_parent")
                                .flatMap(parent -> Stream.of(BELOW_THRESHOLD, ABOVE_THRESHOLD)
                                        .map(traceId -> Arguments.of(executionView, policy, parent, traceId)))));
    }

    @ParameterizedTest(name = "execution={0}, sampler={1}, parent={2}, trace={3}")
    @MethodSource("resumedPolicies")
    void initialResumeAndRetryKeepTheDecisionAndSkipCompletedUserWork(
            boolean executionView, String policy, String parent, String traceId) {
        try (var exporter = InMemorySpanExporter.create()) {
            var completedWork = new AtomicInteger();
            var retryAttempts = new AtomicInteger();
            var config = config(executionView, sampler(policy), extracted(parent, traceId), exporter);
            var runner = replayRunner(config, completedWork, retryAttempts);
            var selected = expected(policy, parent, traceId);

            assertEquals(ExecutionStatus.PENDING, runner.run("result").getStatus());
            assertEquals(1, completedWork.get());
            assertSelection(exporter.getFinishedSpanItems(), selected, traceId);
            exporter.reset();

            runner.advanceTime();
            assertEquals(ExecutionStatus.PENDING, runner.run("result").getStatus());
            assertEquals(1, completedWork.get(), "A completed step must not run again after the durable wait");
            assertEquals(1, retryAttempts.get());
            assertSelection(exporter.getFinishedSpanItems(), selected, traceId);
            exporter.reset();

            runner.advanceTime();
            assertEquals(ExecutionStatus.SUCCEEDED, runner.run("result").getStatus());
            assertEquals(1, completedWork.get(), "A completed step must not run again during retry replay");
            assertEquals(2, retryAttempts.get());
            assertSelection(exporter.getFinishedSpanItems(), selected, traceId);
            if (selected) assertNames(exporter.getFinishedSpanItems(), "Workflow", "Invocation", "retry attempt 2");
            assertFalse(Span.current().getSpanContext().isValid(), "Invocation/attempt context must be cleaned up");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentParentUsesTheParentBasedRootPolicyDespiteSampledAmbient(boolean executionView) {
        var ambient = Span.wrap(SpanContext.create(
                BELOW_THRESHOLD, AMBIENT_PARENT_ID, TraceFlags.getSampled(), TraceState.getDefault()));
        try (var exporter = InMemorySpanExporter.create();
                var ignored = ambient.makeCurrent()) {
            var extracted = new ExtractedContext(BELOW_THRESHOLD, null);
            var config = config(executionView, Sampler.parentBased(Sampler.alwaysOff()), extracted, exporter);
            var runner = LocalDurableTestRunner.create(
                    String.class, (input, ctx) -> ctx.step("work", String.class, stepCtx -> input), config);

            assertEquals(
                    ExecutionStatus.SUCCEEDED, runner.runUntilComplete("result").getStatus());
            assertTrue(exporter.getFinishedSpanItems().isEmpty(), "No propagated parent means root policy applies");
            assertSame(ambient, Span.current(), "The SDK must restore the caller's context");
        }
    }

    static Stream<Arguments> ambientFallbacks() {
        return Stream.of(false, true)
                .flatMap(executionView ->
                        Stream.of(false, true).map(remoteParent -> Arguments.of(executionView, remoteParent)));
    }

    @ParameterizedTest
    @MethodSource("ambientFallbacks")
    void sampledDurableSpansDoNotParentToKnownDroppedAmbientHandler(boolean executionView, boolean remoteParent) {
        var ambient = Span.wrap(SpanContext.create(
                BELOW_THRESHOLD, AMBIENT_PARENT_ID, TraceFlags.getDefault(), TraceState.getDefault()));
        try (var exporter = InMemorySpanExporter.create();
                var ignored = ambient.makeCurrent()) {
            var extracted = extracted(remoteParent ? "not_sampled" : "no_parent", BELOW_THRESHOLD);
            var config = config(executionView, Sampler.alwaysOn(), extracted, exporter);
            var runner = LocalDurableTestRunner.create(
                    String.class, (input, ctx) -> ctx.step("work", String.class, stepCtx -> input), config);

            assertEquals(
                    ExecutionStatus.SUCCEEDED, runner.runUntilComplete("result").getStatus());
            var spans = exporter.getFinishedSpanItems();
            assertSelection(spans, true, BELOW_THRESHOLD);
            assertTrue(
                    spans.stream().noneMatch(span -> span.getParentSpanId().equals(AMBIENT_PARENT_ID)),
                    "Exporter evidence must not contain a sampled child of the known dropped handler span");
            var fallback = remoteParent
                    ? REMOTE_PARENT_ID
                    : spans.stream()
                            .filter(span -> !span.getParentSpanContext().isValid())
                            .findFirst()
                            .orElseThrow()
                            .getSpanId();
            assertEquals(
                    fallback,
                    named(spans, "Invocation").getParentSpanId(),
                    "Use the propagated parent when available, otherwise the synthetic root");
            assertSame(ambient, Span.current());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void suppliedGlobalProviderRetainsOrdinarySamplingAndOwnership(boolean executionView) {
        var exporter = InMemorySpanExporter.create();
        var sampler = DurableSampler.wrap(Sampler.alwaysOn());
        try (var provider = SdkTracerProvider.builder()
                .setSampler(sampler)
                .setIdGenerator(new DeterministicIdGenerator())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            var sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            GlobalOpenTelemetry.set(sdk);
            OtelPluginAutoConfigurationState.markInstalled();
            var pluginConfig = pluginConfig(extracted("not_sampled", BELOW_THRESHOLD));
            var factory = executionView
                    ? ExecutionOtelPlugin.factory(pluginConfig)
                    : InvocationOtelPlugin.factory(pluginConfig);
            var config = DurableConfig.builder().withPlugins(factory).build();
            var tracer = provider.get("application");
            var runner = LocalDurableTestRunner.create(
                    String.class,
                    (input, ctx) -> {
                        return ctx.step("work", String.class, stepCtx -> {
                            tracer.spanBuilder("application-in-step")
                                    .startSpan()
                                    .end();
                            return input;
                        });
                    },
                    config);

            assertEquals(
                    ExecutionStatus.SUCCEEDED, runner.runUntilComplete("result").getStatus());
            assertSame(provider, sdk.getSdkTracerProvider());
            assertSame(sampler, provider.getSampler());
            var ordinary = tracer.spanBuilder("application-after-invocation")
                    .setNoParent()
                    .startSpan();
            assertTrue(ordinary.isRecording(), "Finishing a durable invocation must not close the provider");
            ordinary.end();
            assertNames(
                    exporter.getFinishedSpanItems(),
                    "Workflow",
                    "Invocation",
                    "work",
                    "work attempt 1",
                    "application-in-step",
                    "application-after-invocation");
            assertFalse(Span.current().getSpanContext().isValid());
        }
    }

    private static LocalDurableTestRunner<String, String> replayRunner(
            DurableConfig config, AtomicInteger completedWork, AtomicInteger retryAttempts) {
        return LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    var completed = ctx.step("completed", String.class, stepCtx -> {
                        completedWork.incrementAndGet();
                        return input;
                    });
                    ctx.wait("pause", Duration.ofSeconds(10));
                    return ctx.step(
                            "retry",
                            String.class,
                            stepCtx -> {
                                if (retryAttempts.incrementAndGet() == 1) throw new IllegalStateException("retry once");
                                return completed;
                            },
                            StepConfig.builder()
                                    .retryStrategy((error, attempt) -> attempt == 1
                                            ? RetryDecision.retry(Duration.ofSeconds(10))
                                            : RetryDecision.fail())
                                    .build());
                },
                config);
    }

    private static DurableConfig config(
            boolean executionView, Sampler sampler, ExtractedContext extracted, InMemorySpanExporter exporter) {
        var builder =
                SdkTracerProvider.builder().setSampler(sampler).addSpanProcessor(SimpleSpanProcessor.create(exporter));
        DurableExecutionPluginFactory factory = executionView
                ? ExecutionOtelPlugin.factory(builder, pluginConfig(extracted))
                : InvocationOtelPlugin.factory(builder, pluginConfig(extracted));
        return DurableConfig.builder().withPlugins(factory).build();
    }

    private static OtelPluginConfig pluginConfig(ExtractedContext extracted) {
        return OtelPluginConfig.builder()
                .contextExtractor(() -> extracted)
                .enableMdc(false)
                .build();
    }

    private static ExtractedContext extracted(String parent, String traceId) {
        var parentId = switch (parent) {
            case "no_parent" -> null;
            case "invalid_parent" -> "0000000000000000";
            default -> REMOTE_PARENT_ID;
        };
        var sampling = switch (parent) {
            case "not_sampled" -> ExtractedContext.Sampling.NOT_SAMPLED;
            case "unset" -> ExtractedContext.Sampling.UNDECIDED;
            default -> ExtractedContext.Sampling.SAMPLED;
        };
        return new ExtractedContext(traceId, parentId, sampling);
    }

    private static Sampler sampler(String policy) {
        return switch (policy) {
            case "always_on" -> Sampler.alwaysOn();
            case "always_off" -> Sampler.alwaysOff();
            case "traceidratio" -> Sampler.traceIdRatioBased(0.5);
            case "parentbased_always_on" -> Sampler.parentBased(Sampler.alwaysOn());
            case "parentbased_always_off" -> Sampler.parentBased(Sampler.alwaysOff());
            case "parentbased_traceidratio" -> Sampler.parentBased(Sampler.traceIdRatioBased(0.5));
            default -> throw new IllegalArgumentException(policy);
        };
    }

    private static boolean expected(String policy, String parent, String traceId) {
        var hasParent = !parent.equals("no_parent") && !parent.equals("invalid_parent");
        if (policy.startsWith("parentbased_") && hasParent) return parent.equals("sampled");
        if (policy.endsWith("always_on")) return true;
        if (policy.endsWith("always_off")) return false;
        return traceId.equals(BELOW_THRESHOLD);
    }

    private static void assertSelection(List<SpanData> spans, boolean selected, String traceId) {
        assertEquals(!selected, spans.isEmpty(), "The configured policy applies to every durable span");
        assertTrue(spans.stream().allMatch(span -> span.getTraceId().equals(traceId)), "Canonical trace identity");
        assertTrue(spans.stream().allMatch(span -> span.getSpanContext().isSampled()), "All exported spans sampled");
    }

    private static void assertNames(List<SpanData> spans, String... names) {
        for (var name : names) {
            assertTrue(spans.stream().anyMatch(span -> span.getName().equals(name)), "Missing span: " + name);
        }
    }

    private static SpanData named(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
