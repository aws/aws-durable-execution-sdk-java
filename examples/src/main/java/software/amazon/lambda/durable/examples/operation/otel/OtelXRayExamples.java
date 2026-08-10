// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.otel;

import static software.amazon.lambda.durable.logging.DurableLogger.getLogger;
import static software.amazon.lambda.durable.operation.DurableContextOperation.runInChildContext;
import static software.amazon.lambda.durable.operation.DurableMapOperation.map;
import static software.amazon.lambda.durable.operation.DurableParallelOperation.parallel;
import static software.amazon.lambda.durable.operation.DurableStepOperation.step;

import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.otel.InvocationOtelPlugin;

/**
 * OTel examples for map, parallel, and nested context operations. These are local-only examples (not deployed to
 * Lambda) used to verify the OTel plugin doesn't break execution for these patterns.
 */
public final class OtelXRayExamples {

    private OtelXRayExamples() {}

    private static DurableConfig localOtelConfig() {
        var otelPlugin = new InvocationOtelPlugin(
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
        public String handleRequest(GreetingRequest input) {
            getLogger().info("Starting OTel X-Ray map example for {}", input.getName());

            var items = List.of("alpha", "beta", "gamma");
            var result = map(
                    "process-items",
                    items,
                    String.class,
                    item -> step("transform-" + item, String.class, () -> item.toUpperCase()));

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
        public String handleRequest(GreetingRequest input) {
            getLogger().info("Starting OTel X-Ray parallel example for {}", input.getName());

            var parallel = parallel("fan-out");
            try (parallel) {
                parallel.branch(
                        "branch-a",
                        String.class,
                        childCtx -> step("step-a", String.class, () -> "A: " + input.getName()));
                parallel.branch(
                        "branch-b",
                        String.class,
                        childCtx -> step("step-b", String.class, () -> "B: " + input.getName()));
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
        public String handleRequest(GreetingRequest input) {
            getLogger().info("Starting OTel X-Ray nested context example for {}", input.getName());

            return runInChildContext("outer", String.class, () -> {
                var intermediate = step("outer-step", String.class, () -> "Hello, " + input.getName());
                return runInChildContext("inner", String.class, () -> {
                    return step("deep-step", String.class, () -> intermediate.toUpperCase() + "!");
                });
            });
        }
    }
}
