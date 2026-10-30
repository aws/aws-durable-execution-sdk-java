// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.lmi;

import com.amazonaws.services.lambda.runtime.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import software.amazon.lambda.durable.otel.ExecutionOtelPlugin;
import software.amazon.lambda.durable.otel.InvocationOtelPlugin;
import software.amazon.lambda.durable.otel.OtelPluginConfig;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;

/** Process-owned real OTel provider with a controllable evidence exporter. */
final class OtelRuntime implements AutoCloseable {
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String identity = UUID.randomUUID().toString();
    private final String view;
    private final DurableExecutionPluginFactory factory;
    private final Map<DurableExecutionPlugin, String> instances = Collections.synchronizedMap(new WeakHashMap<>());
    private final OtelLedgerProcessor processor;

    OtelRuntime(String view) {
        this(view, OtelRuntime::waitForExportRelease);
    }

    OtelRuntime(String view, Consumer<InvocationTrace> hold) {
        this.view = view;
        processor = new OtelLedgerProcessor(hold);
        var builder = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).addSpanProcessor(processor);
        var config = OtelPluginConfig.builder().enableMdc(true).build();
        factory = switch (view) {
            case "invocation" -> InvocationOtelPlugin.factory(builder, config);
            case "execution" -> ExecutionOtelPlugin.factory(builder, config);
            default -> throw new IllegalArgumentException("Unknown OTel view");
        };
    }

    void enter(InvocationTrace trace, Context context) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("view", view);
        fields.put("provider", identity);
        fields.put("exporterShutdowns", processor.shutdowns.get());
        fields.put("carrierSource", "runtime_context");
        try {
            fields.put("carrier", context.getXrayTraceId());
        } catch (RuntimeException error) {
            fields.put("carrier", null);
            fields.put("carrierError", error.getClass().getSimpleName());
        }
        trace.event("OTEL_ENTER", fields);
    }

    DurableExecutionPluginFactory forInvocation(InvocationTrace trace) {
        return info -> {
            var plugin = ObservedOtelPlugin.withOwner(trace, () -> factory.createPlugin(info));
            var instance =
                    instances.computeIfAbsent(plugin, key -> UUID.randomUUID().toString());
            trace.event(
                    "OTEL_FACTORY",
                    Map.of(
                            "pluginInstance",
                            instance,
                            "pluginExecution",
                            info.durableExecutionArn(),
                            "firstInvocation",
                            info.isFirstInvocation(),
                            "capturedHeader",
                            info.xRayTraceId() == null ? "" : info.xRayTraceId()));
            return new ObservedOtelPlugin(plugin, trace, processor.shutdowns::get);
        };
    }

    @Override
    public void close() {
        processor.shutdown().join(5, TimeUnit.SECONDS);
    }

    private static void waitForExportRelease(InvocationTrace trace) {
        var until = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < until) {
            try {
                var request = HttpRequest.newBuilder(URI.create(trace.input.exportControlUrl()))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IllegalStateException("Export gate read failed");
                if ("release".equals(response.body().trim())) return;
                if (!"hold".equals(response.body().trim())) throw new IllegalStateException("Invalid export gate");
                new CountDownLatch(1).await(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Export gate interrupted", interrupted);
            } catch (IOException failure) {
                throw new IllegalStateException("Export gate unavailable", failure);
            }
        }
        throw new IllegalStateException("Export fixture escape; never acceptance");
    }

    static Map<String, Object> span(SpanData data) {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", data.getName());
        result.put("traceId", data.getTraceId());
        result.put("spanId", data.getSpanId());
        result.put("parentId", data.getParentSpanId());
        result.put("sampled", data.getSpanContext().isSampled());
        result.put("startNanos", data.getStartEpochNanos());
        result.put("endNanos", data.getEndEpochNanos());
        result.put("status", data.getStatus().getStatusCode().name());
        var attributes = new LinkedHashMap<String, Object>();
        data.getAttributes().forEach((key, value) -> attributes.put(key.getKey(), value));
        result.put("attributes", attributes);
        return result;
    }
}
