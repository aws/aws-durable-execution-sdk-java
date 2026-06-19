// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.otel;

import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.otel.OpenTelemetryDurablePlugin;

/**
 * OTel + X-Ray example: map operation that processes items concurrently.
 *
 * <p>Exercises map operation tracing — verifies spans for map operation + each item step.
 */
public class OtelXRayMapExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected DurableConfig createConfiguration() {
        var otlpExporter = OtlpGrpcSpanExporter.getDefault();
        var otelPlugin = new OpenTelemetryDurablePlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(otlpExporter)));
        return DurableConfig.builder().withPlugins(otelPlugin).build();
    }

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        context.getLogger().info("Starting OTel X-Ray map example for {}", input.getName());

        var items = List.of("alpha", "beta", "gamma");
        var result = context.map(
                "process-items",
                items,
                String.class,
                (item, index, childCtx) ->
                        childCtx.step("transform-" + item, String.class, stepCtx -> item.toUpperCase()));

        return "Mapped " + result.succeeded().size() + " items";
    }
}
