// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;
import software.amazon.lambda.durable.plugin.UserFunctionEndInfo;
import software.amazon.lambda.durable.plugin.UserFunctionOutcome;
import software.amazon.lambda.durable.plugin.UserFunctionStartInfo;

/** Actual environment/autoconfiguration acceptance probe; run through run_sampler_matrix.py. */
public final class ConfiguredSamplerProbe {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String PARENT = "1111111111111111";
    private static final List<String> TRACE_IDS =
            List.of("12345678901234560000000000000001", "12345678901234567fffffffffffffff");

    public static void main(String[] args) {
        var mode = args[0];
        var expectedSampler = sampler(args[1], Double.parseDouble(args[2]));
        var exporter = InMemorySpanExporter.create();
        if (mode.equals("invalid")) {
            rejectsInvalidConfiguration(exporter);
            return;
        }
        try (var sdk = mode.equals("builder") ? null : autoConfigure(exporter, mode)) {
            if (sdk != null) {
                check(sdk.getSdkTracerProvider().getSampler() instanceof DurableSampler, "SPI sampler not installed");
            }
            var cases = runMatrix(exporter, sdk, expectedSampler);
            System.out.println(
                    "PASS " + cases + " scenarios (initial + resume), sampler=" + args[1] + ", mode=" + mode);
        } finally {
            GlobalOpenTelemetry.resetForTest();
        }
    }

    private static int runMatrix(InMemorySpanExporter exporter, OpenTelemetrySdk sdk, Sampler sampler) {
        var cases = 0;
        for (var view : List.of("execution", "invocation")) {
            for (var traceId : TRACE_IDS) {
                for (var parent : List.of("sampled", "unsampled", "unset", "root", "root-unsampled", "invalid")) {
                    for (var ambient : List.of(false, true)) {
                        runScenario(view, traceId, parent, ambient, exporter, sdk, sampler);
                        cases++;
                    }
                }
            }
        }
        return cases;
    }

    private static OpenTelemetrySdk autoConfigure(InMemorySpanExporter exporter, String mode) {
        var builder = AutoConfiguredOpenTelemetrySdk.builder()
                .disableShutdownHook()
                .setResultAsGlobal()
                .addTracerProviderCustomizer(
                        (provider, properties) -> provider.addSpanProcessor(SimpleSpanProcessor.create(exporter)));
        if (mode.equals("customizer")) {
            builder.addSamplerCustomizer((configured, properties) -> Sampler.alwaysOff());
        }
        // ServiceLoader discovers the plugin's production SPI customizer from its own artifact.
        return builder.build().getOpenTelemetrySdk();
    }

    private static void rejectsInvalidConfiguration(InMemorySpanExporter exporter) {
        try (var ignored = autoConfigure(exporter, "auto")) {
            throw new AssertionError("Invalid sampler configuration was accepted");
        } catch (ConfigurationException expected) {
            System.out.println("PASS provider rejected invalid configuration: " + expected.getMessage());
        }
    }

    private static void runScenario(
            String view,
            String traceId,
            String parent,
            boolean ambient,
            InMemorySpanExporter exporter,
            OpenTelemetrySdk sdk,
            Sampler expectedSampler) {
        exporter.reset();
        var config = OtelPluginConfig.builder().enableMdc(false).build();
        var factory = factory(view, config, exporter, sdk, expectedSampler);
        var arn = "arn:aws:lambda:us-east-1:123:function:sampling:$LATEST/durable/" + view + "-" + parent + "-"
                + traceId + "-" + ambient;
        var sampled = samples(expectedSampler, originalParent(traceId, parent), traceId);
        var droppedParents = new ArrayList<String>();
        for (var invocation = 0; invocation < 2; invocation++) {
            var info = invocationInfo(arn, invocation, traceId, parent);
            if (ambient) {
                droppedParents.add(invokeWithDroppedAmbient(factory, info, invocation, traceId));
            } else {
                invoke(factory, info, invocation);
            }
            check(!Span.current().getSpanContext().isValid(), "Invocation leaked active context");
            verifyExport(exporter.getFinishedSpanItems(), traceId, parent, sampled, invocation);
            verifyNoDroppedParents(exporter.getFinishedSpanItems(), droppedParents);
        }
        if (sdk != null) {
            verifyOrdinarySpan(sdk.getSdkTracerProvider(), expectedSampler, traceId, parent);
        }
    }

    private static InvocationInfo invocationInfo(String arn, int invocation, String traceId, String parent) {
        return new InvocationInfo(
                "request-" + invocation,
                arn,
                invocation == 0,
                START,
                null,
                Map.of(),
                Map.of(),
                header(traceId, parent));
    }

    private static void verifyNoDroppedParents(List<SpanData> spans, List<String> droppedParents) {
        check(
                spans.stream().noneMatch(span -> droppedParents.contains(span.getParentSpanId())),
                "Durable span parented to known dropped ambient span: "
                        + spans.stream()
                                .filter(span -> droppedParents.contains(span.getParentSpanId()))
                                .map(span -> span.getName() + " parent=" + span.getParentSpanId() + " id="
                                        + span.getSpanId())
                                .toList());
    }

    private static String invokeWithDroppedAmbient(
            DurableExecutionPluginFactory factory, InvocationInfo info, int invocation, String traceId) {
        try (var provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOff())
                .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()))
                .build()) {
            var ambient = provider.get("dropped-handler")
                    .spanBuilder("handler")
                    .setParent(originalParent(traceId, "sampled"))
                    .startSpan();
            check(!ambient.isRecording(), "Ambient fixture must be dropped");
            check(!ambient.getSpanContext().getSpanId().equals(PARENT), "Ambient fixture must have its own span ID");
            try (var ignored = ambient.makeCurrent()) {
                invoke(factory, info, invocation);
                check(Span.current() == ambient, "Invocation did not restore ambient context");
            } finally {
                ambient.end();
            }
            return ambient.getSpanContext().getSpanId();
        }
    }

    private static DurableExecutionPluginFactory factory(
            String view,
            OtelPluginConfig config,
            InMemorySpanExporter exporter,
            OpenTelemetrySdk sdk,
            Sampler sampler) {
        if (sdk != null) {
            return view.equals("execution")
                    ? ExecutionOtelPlugin.factory(config)
                    : InvocationOtelPlugin.factory(config);
        }
        var builder =
                SdkTracerProvider.builder().setSampler(sampler).addSpanProcessor(SimpleSpanProcessor.create(exporter));
        return view.equals("execution")
                ? ExecutionOtelPlugin.factory(builder, config)
                : InvocationOtelPlugin.factory(builder, config);
    }

    private static void invoke(DurableExecutionPluginFactory factory, InvocationInfo info, int invocation) {
        var plugin = factory.createPlugin(info);
        plugin.onInvocationStart(info);
        completeStep(plugin, "step-" + invocation);
        plugin.onInvocationEnd(new InvocationEndInfo(
                info.requestId(),
                info.durableExecutionArn(),
                info.isFirstInvocation(),
                invocation == 0 ? InvocationStatus.PENDING : InvocationStatus.SUCCEEDED,
                null));
    }

    private static void completeStep(DurableExecutionPlugin plugin, String id) {
        var start = Instant.now();
        plugin.onOperationStart(new OperationInfo(id, id, "STEP", "Step", null, start, null, null, false));
        plugin.onUserFunctionStart(new UserFunctionStartInfo(id, id, "STEP", "Step", null, start, false, 1));
        plugin.onUserFunctionEnd(new UserFunctionEndInfo(
                id, id, "STEP", "Step", null, start, Instant.now(), false, 1, UserFunctionOutcome.SUCCEEDED, null));
        plugin.onOperationEnd(
                new OperationEndInfo(id, id, "STEP", "Step", null, start, Instant.now(), "SUCCEEDED", 1, false, null));
    }

    private static void verifyExport(
            List<SpanData> spans, String traceId, String parent, boolean sampled, int invocation) {
        if (!sampled) {
            check(spans.isEmpty(), "Dropped execution exported " + spans.size() + " spans for parent " + parent);
            return;
        }
        check(!spans.isEmpty(), "Sampled execution exported no spans for parent " + parent);
        for (var span : spans) {
            check(span.getTraceId().equals(traceId), "Canonical trace ID changed");
            check(span.getSpanContext().isSampled(), "Durable span did not inherit resolved sampling");
        }
        check(countNamed(spans, "Invocation") == invocation + 1, "Invocation export count");
        check(countNamed(spans, "Workflow") == invocation, "Workflow should be exported on terminal invocation");
        if (hasParent(parent)) {
            check(
                    spans.stream()
                            .filter(span -> span.getName().equals("Invocation"))
                            .allMatch(span -> span.getParentSpanId().equals(PARENT)),
                    "Propagated parent fallback changed");
        }
        check(countAttribute(spans, SpanAttributes.DURABLE_ATTEMPT_OUTCOME) == invocation + 1, "Attempt export count");
        check(
                countAttribute(spans, SpanAttributes.DURABLE_OPERATION_ID) == 2L * (invocation + 1),
                "Operation export count");
        var roots = spans.stream()
                .filter(span ->
                        Boolean.TRUE.equals(span.getAttributes().get(SpanAttributes.DURABLE_EXECUTION_SYNTHETIC_ROOT)))
                .count();
        check(roots == (hasParent(parent) ? 0 : invocation + 1), "Synthetic root export count");
    }

    private static long countNamed(List<SpanData> spans, String name) {
        return spans.stream().filter(span -> span.getName().equals(name)).count();
    }

    private static long countAttribute(List<SpanData> spans, AttributeKey<?> key) {
        return spans.stream()
                .filter(span -> span.getAttributes().get(key) != null)
                .count();
    }

    private static void verifyOrdinarySpan(SdkTracerProvider provider, Sampler sampler, String traceId, String parent) {
        var context = originalParent(traceId, parent);
        var span = provider.get("ordinary-application")
                .spanBuilder("ordinary")
                .setParent(context)
                .startSpan();
        check(span.getSpanContext().isValid(), "Provider was shut down by plugin");
        check(
                span.getSpanContext().isSampled()
                        == samples(sampler, context, span.getSpanContext().getTraceId()),
                "Ordinary span sampling changed or provider was shut down");
        span.end();
    }

    private static boolean samples(Sampler sampler, Context parent, String traceId) {
        return sampler.shouldSample(parent, traceId, "Workflow", SpanKind.INTERNAL, Attributes.empty(), List.of())
                        .getDecision()
                == SamplingDecision.RECORD_AND_SAMPLE;
    }

    private static Context originalParent(String traceId, String parent) {
        return hasParent(parent)
                ? Context.root()
                        .with(Span.wrap(SpanContext.createFromRemoteParent(
                                traceId,
                                PARENT,
                                parent.equals("sampled") ? TraceFlags.getSampled() : TraceFlags.getDefault(),
                                TraceState.getDefault())))
                : Context.root();
    }

    private static boolean hasParent(String parent) {
        return parent.equals("sampled") || parent.equals("unsampled") || parent.equals("unset");
    }

    private static String header(String traceId, String parent) {
        var root = "Root=1-" + traceId.substring(0, 8) + "-" + traceId.substring(8);
        var remote =
                hasParent(parent) ? ";Parent=" + PARENT : parent.equals("invalid") ? ";Parent=0000000000000000" : "";
        var flag = parent.equals("unset")
                ? ""
                : parent.equals("unsampled") || parent.equals("root-unsampled") ? ";Sampled=0" : ";Sampled=1";
        return root + remote + flag;
    }

    private static Sampler sampler(String name, double ratio) {
        return switch (name) {
            case "always_on" -> Sampler.alwaysOn();
            case "always_off" -> Sampler.alwaysOff();
            case "traceidratio" -> Sampler.traceIdRatioBased(ratio);
            case "parentbased_always_on" -> Sampler.parentBased(Sampler.alwaysOn());
            case "parentbased_always_off" -> Sampler.parentBased(Sampler.alwaysOff());
            case "parentbased_traceidratio" -> Sampler.parentBased(Sampler.traceIdRatioBased(ratio));
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
