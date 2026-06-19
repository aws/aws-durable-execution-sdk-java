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
 * OTel + X-Ray example: parallel operation with multiple branches.
 *
 * <p>Exercises parallel operation tracing — verifies spans for parallel + branch steps.
 */
public class OtelXRayParallelExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected DurableConfig createConfiguration() {
        var otlpExporter = OtlpGrpcSpanExporter.getDefault();
        var otelPlugin = new OpenTelemetryDurablePlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(otlpExporter)));
        return DurableConfig.builder().withPlugins(otelPlugin).build();
    }

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        context.getLogger().info("Starting OTel X-Ray parallel example for {}", input.getName());

        var parallel = context.parallel("fan-out");
        try (parallel) {
            parallel.branch(
                    "branch-a",
                    String.class,
                    childCtx -> childCtx.step("step-a", String.class, stepCtx -> "A: " + input.getName()));
            parallel.branch(
                    "branch-b",
                    String.class,
                    childCtx -> childCtx.step("step-b", String.class, stepCtx -> "B: " + input.getName()));
        }
        var result = parallel.get();

        return "Parallel completed: " + result.succeeded() + " branches";
    }
}
