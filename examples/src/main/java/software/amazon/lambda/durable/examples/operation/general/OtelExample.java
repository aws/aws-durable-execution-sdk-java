// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.general;

import static software.amazon.lambda.durable.logging.DurableLogger.getLogger;
import static software.amazon.lambda.durable.operation.DurableStepOperation.step;

import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.otel.InvocationOtelPlugin;

/**
 * Example demonstrating OpenTelemetry instrumentation with the Durable Execution SDK.
 *
 * <p>This handler configures the OTel plugin with:
 *
 * <ul>
 *   <li>Deterministic trace/span IDs (all invocations of the same execution share one trace)
 *   <li>MDC log enrichment (traceId, spanId, traceSampled in every log line)
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
        var otelPlugin = new InvocationOtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create())));

        return DurableConfig.builder().withPlugins(otelPlugin).build();
    }

    @Override
    public String handleRequest(GreetingRequest input) {
        // Log with MDC — traceId and spanId will be in the JSON output
        getLogger().info("Starting OTel example for {}", input.getName());

        var greeting = step("create-greeting", String.class, () -> {
            getLogger().info("Inside step — this log has trace context in MDC");
            return "Hello, " + input.getName();
        });

        var result = step("transform", String.class, () -> greeting.toUpperCase() + "!");

        getLogger().info("OTel example complete: {}", result);
        return result;
    }
}
