// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.config.ParallelConfig;

/** Parallel hierarchy scenario for OTel requirement otel-invocation-6. */
public final class Otel6Parallel extends OtelConformanceHandler<List<String>> {

    @Override
    public List<String> handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "parallel-hierarchy");
        var parallel = context.parallel(
                "otel-parallel", ParallelConfig.builder().maxConcurrency(1).build());
        DurableFuture<String> branchA;
        DurableFuture<String> branchB;
        try (parallel) {
            branchA = parallel.branch(
                    "otel-parallel-branch-a",
                    String.class,
                    branch -> branch.step("otel-parallel-step-a", String.class, step -> "a"));
            branchB = parallel.branch(
                    "otel-parallel-branch-b",
                    String.class,
                    branch -> branch.step("otel-parallel-step-b", String.class, step -> "b"));
        }
        return List.of(branchA.get(), branchB.get());
    }
}
