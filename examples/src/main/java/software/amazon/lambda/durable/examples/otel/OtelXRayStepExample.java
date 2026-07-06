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
import software.amazon.lambda.durable.otel.OtelPlugin;

/**
 * OTel + X-Ray example: simple steps in a single invocation.
 *
 * <p>Exports spans via OTLP to the ADOT Lambda Layer collector, which forwards them to X-Ray. Requires:
 *
 * <ul>
 *   <li>{@code Tracing: Active} on the Lambda function
 *   <li>ADOT Lambda Layer added to the function (for the OTLP collector)
 * </ul>
 *
 * <p>Expected trace structure in X-Ray:
 *
 * <pre>
 * invocation
 * ├── create-greeting
 * │   └── create-greeting attempt 1
 * └── transform
 *     └── transform attempt 1
 * </pre>
 */
public class OtelXRayStepExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected DurableConfig createConfiguration() {
        // OTLP exporter sends spans to the ADOT collector (localhost:4317 by default)
        var otlpExporter = OtlpGrpcSpanExporter.getDefault();

        var otelPlugin =
                new OtelPlugin(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(otlpExporter)));

        return DurableConfig.builder().withPlugins(otelPlugin).build();
    }

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        context.getLogger().info("Starting OTel X-Ray step example for {}", input.getName());

        var greeting = context.step("create-greeting", String.class, stepCtx -> "Hello, " + input.getName());

        var result = context.step("transform", String.class, stepCtx -> greeting.toUpperCase() + "!");

        context.getLogger().info("OTel X-Ray step example complete: {}", result);
        return result;
    }
}
