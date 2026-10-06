// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.ParallelConfig;

/** Active user-function context scenario for OTel requirement 22 in both views. */
public final class Otel22UserFunctionContext extends OtelConformanceHandler<String> {

    @Override
    public String handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "user-function-context");
        probe("handler");
        context.step("otel-context-step", String.class, step -> {
            probe("step");
            return "step";
        });
        runChild(context);
        runParallel(context);
        runMap(context);
        probe("handler-restored");
        context.wait("otel-context-resume", Duration.ofSeconds(1));
        probe("handler-after-resume");
        return "context-complete";
    }

    private static void runChild(DurableContext context) {
        context.runInChildContext("otel-context-child", String.class, child -> {
            probe("child");
            var result = child.step("otel-context-child-step", String.class, step -> {
                probe("child-step");
                return "child";
            });
            probe("child-restored");
            return result;
        });
    }

    private static void runMap(DurableContext context) {
        context.map(
                "otel-context-map",
                List.of(0, 1),
                Integer.class,
                (item, index, iteration) -> {
                    probe("map-" + index);
                    return iteration.step("otel-context-map-step-" + index, Integer.class, step -> {
                        probe("map-step-" + index);
                        return item;
                    });
                },
                MapConfig.builder()
                        .maxConcurrency(2)
                        .itemNamer((item, index) -> "otel-context-iteration-" + index)
                        .build());
    }

    private static void runParallel(DurableContext context) {
        var parallel = context.parallel(
                "otel-context-parallel",
                ParallelConfig.builder().maxConcurrency(2).build());
        try (parallel) {
            parallel.branch("otel-context-branch-a", String.class, branch -> {
                probe("parallel-a");
                return branch.step("otel-context-branch-step-a", String.class, step -> {
                    probe("parallel-step-a");
                    return "a";
                });
            });
            parallel.branch("otel-context-branch-b", String.class, branch -> {
                probe("parallel-b");
                return branch.step("otel-context-branch-step-b", String.class, step -> {
                    probe("parallel-step-b");
                    return "b";
                });
            });
        }
    }

    private static void probe(String label) {
        if (!Span.current().getSpanContext().isValid()) {
            throw new IllegalStateException("No active span context for " + label);
        }
        var span = GlobalOpenTelemetry.getTracer("durable-conformance")
                .spanBuilder("conformance." + label)
                .setAttribute("conformance.callback", label)
                .startSpan();
        span.end();
    }
}
