// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.otel;

import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.otel.OpenTelemetryDurablePlugin;

/**
 * OTel + X-Ray example: nested child contexts with inner steps.
 *
 * <p>Exercises nested context tracing — verifies span hierarchy for outer → inner → step.
 */
public class OtelXRayNestedContextExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected DurableConfig createConfiguration() {
        var otlpExporter = OtlpGrpcSpanExporter.getDefault();
        var otelPlugin = new OpenTelemetryDurablePlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(otlpExporter)));
        return DurableConfig.builder().withPlugins(otelPlugin).build();
    }

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        context.getLogger().info("Starting OTel X-Ray nested context example for {}", input.getName());

        return context.runInChildContext("outer", String.class, outerCtx -> {
            var intermediate = outerCtx.step("outer-step", String.class, stepCtx -> "Hello, " + input.getName());
            return outerCtx.runInChildContext("inner", String.class, innerCtx -> {
                return innerCtx.step("deep-step", String.class, stepCtx -> intermediate.toUpperCase() + "!");
            });
        });
    }
}
