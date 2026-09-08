// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.config.ParallelConfig;

/** Failed parallel-branch scenario for OTel requirement otel-invocation-13. */
public final class Otel13ParallelFailure extends OtelConformanceHandler<Void> {

    @Override
    public Void handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "parallel-failure");
        var parallel = context.parallel(
                "otel-failed-parallel",
                ParallelConfig.builder().maxConcurrency(1).build());
        DurableFuture<Void> failedBranch;
        try (parallel) {
            failedBranch = parallel.branch("otel-failed-parallel-branch", Void.class, branch -> {
                throw new RuntimeException("Intentional parallel branch failure");
            });
        }
        return failedBranch.get();
    }
}
