// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.otel;

import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.ExampleTemplate;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.otel.OtelPlugin;

/**
 * OTel + X-Ray example that uses the no-arg plugin constructor with a global SDK provider.
 *
 * <p>The global provider exports through the ADOT collector layer. {@link OtelPlugin#OtelPlugin()} copies that
 * provider's export pipeline, then installs the durable deterministic ID generator for the plugin's provider.
 */
@ExampleTemplate(tracing = true)
public class OtelXRayDefaultConstructorExample extends DurableHandler<GreetingRequest, String> {

    static {
        var otlpExporter = OtlpGrpcSpanExporter.getDefault();
        var tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(otlpExporter))
                .build();
        OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).buildAndRegisterGlobal();
    }

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new OtelPlugin()).build();
    }

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        context.getLogger().info("Starting OTel X-Ray default constructor example for {}", input.getName());

        var greeting = context.step("default-create-greeting", String.class, stepCtx -> "Hello, " + input.getName());

        var result = context.step("default-transform", String.class, stepCtx -> greeting.toUpperCase() + "!");

        context.getLogger().info("OTel X-Ray default constructor example complete: {}", result);
        return result;
    }
}
