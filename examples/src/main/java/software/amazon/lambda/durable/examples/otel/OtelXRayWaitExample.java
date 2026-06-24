// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.otel;

import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.otel.OtelPlugin;

/**
 * OTel + X-Ray example: step → wait → step pattern that forces multiple Lambda invocations.
 *
 * <p>This handler exercises the critical multi-invocation tracing scenario:
 *
 * <ol>
 *   <li>Invocation 1: "before-wait" step completes → wait suspends execution
 *   <li>Invocation 2: replays "before-wait" (no-op) → wait completes → "after-wait" step runs
 * </ol>
 *
 * <p>Exports spans via OTLP to the ADOT Lambda Layer collector. Requires:
 *
 * <ul>
 *   <li>{@code Tracing: Active} on the Lambda function
 *   <li>ADOT Lambda Layer added to the function
 *   <li>{@code AWS_LAMBDA_EXEC_WRAPPER=/opt/otel-handler} environment variable
 * </ul>
 *
 * <p>Expected trace structure in X-Ray (all under one trace ID — backend propagates same Root):
 *
 * <pre>
 * Trace (single trace ID across both invocations)
 * ├── durable.invocation (invocation 1)
 * │   ├── durable.step:before-wait
 * │   │   └── durable.step:before-wait [attempt 1]
 * │   └── durable.wait:pause (ended as PENDING)
 * └── durable.invocation (invocation 2)
 *     ├── durable.wait:pause (completed)
 *     └── durable.step:after-wait
 *         └── durable.step:after-wait [attempt 1]
 * </pre>
 */
public class OtelXRayWaitExample extends DurableHandler<GreetingRequest, String> {

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
        context.getLogger().info("Starting OTel X-Ray wait example for {}", input.getName());

        var before = context.step("before-wait", String.class, stepCtx -> "Prepared: " + input.getName());

        // This wait forces Lambda to suspend and re-invoke after the duration
        context.wait("pause", Duration.ofSeconds(5));

        var after = context.step("after-wait", String.class, stepCtx -> before + " | Resumed and completed");

        context.getLogger().info("OTel X-Ray wait example complete: {}", after);
        return after;
    }
}
