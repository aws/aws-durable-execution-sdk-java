// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.general;

import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.otel.OpenTelemetryDurablePlugin;

/**
 * Example demonstrating OpenTelemetry instrumentation with the Durable Execution SDK.
 *
 * <p>This handler configures the OTel plugin with:
 *
 * <ul>
 *   <li>Deterministic trace/span IDs (all invocations of the same execution share one trace)
 *   <li>MDC log enrichment (trace_id and span_id in every log line)
 *   <li>Logging exporter (spans printed to stdout → CloudWatch Logs)
 * </ul>
 *
 * <p>In production, replace {@code LoggingSpanExporter} with {@code OtlpGrpcSpanExporter} to send spans to an OTLP
 * collector (X-Ray, Datadog, etc.).
 *
 * <p>Expected trace structure:
 *
 * <pre>
 * durable.invocation
 * ├── durable.step:create-greeting [attempt 1]
 * ├── durable.step:create-greeting (operation, backfilled)
 * ├── durable.step:transform [attempt 1]
 * └── durable.step:transform (operation, backfilled)
 * </pre>
 */
public class OtelExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected DurableConfig createConfiguration() {
        var otelPlugin = new OpenTelemetryDurablePlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create())));

        return DurableConfig.builder().withPlugins(otelPlugin).build();
    }

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        // Log with MDC — trace_id and span_id will be in the JSON output
        context.getLogger().info("Starting OTel example for {}", input.getName());

        var greeting = context.step("create-greeting", String.class, stepCtx -> {
            context.getLogger().info("Inside step — this log has trace context in MDC");
            return "Hello, " + input.getName();
        });

        var result = context.step("transform", String.class, stepCtx -> greeting.toUpperCase() + "!");

        context.getLogger().info("OTel example complete: {}", result);
        return result;
    }
}
