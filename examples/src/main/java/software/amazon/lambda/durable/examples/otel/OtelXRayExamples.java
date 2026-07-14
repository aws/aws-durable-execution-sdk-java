// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.otel;

import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.otel.OtelPlugin;

/**
 * OTel examples for map, parallel, and nested context operations. These are local-only examples (not deployed to
 * Lambda) used to verify the OTel plugin doesn't break execution for these patterns.
 */
public final class OtelXRayExamples {

    private OtelXRayExamples() {}

    private static DurableConfig localOtelConfig() {
        var otelPlugin = new OtelPlugin(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create())));
        return DurableConfig.builder().withPlugins(otelPlugin).build();
    }

    /** Map operation that processes items concurrently. */
    public static class MapExample extends DurableHandler<GreetingRequest, String> {

        @Override
        protected DurableConfig createConfiguration() {
            return localOtelConfig();
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

    /** Parallel operation with multiple branches. */
    public static class ParallelExample extends DurableHandler<GreetingRequest, String> {

        @Override
        protected DurableConfig createConfiguration() {
            return localOtelConfig();
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

    /** Nested child contexts with inner steps. */
    public static class NestedContextExample extends DurableHandler<GreetingRequest, String> {

        @Override
        protected DurableConfig createConfiguration() {
            return localOtelConfig();
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
}
