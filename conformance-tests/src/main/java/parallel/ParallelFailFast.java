// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package parallel;

import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.model.ParallelResult;

/**
 * 8-6: Parallel with a fail-fast completion config (tolerated-failure-count=0) stops on the first branch failure and
 * does not start remaining branches
 *
 * <p>Fail-fast is configured explicitly via {@code CompletionConfig.allSuccessful()} (toleratedFailureCount=0),
 * matching the requirement's fail-fast completion config. Note: Java's default {@code ParallelConfig} is
 * {@code allCompleted()} (run every branch, tolerate all failures), so fail-fast must be requested explicitly — unlike
 * the JS/Python default. The reframed requirement specifies the explicit config, so all SDKs are uniform.
 *
 * <p>Branches return/throw directly (no inner step), matching the step-free branch history. The parallel operation
 * itself does not rethrow; the handler projects the batch summary.
 */
public class ParallelFailFast extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.allSuccessful())
                .build();
        ParallelDurableFuture parallel = context.parallel("failfast", config);
        try (parallel) {
            parallel.branch("branch-0", String.class, branch -> "ok");
            parallel.branch("branch-1", String.class, branch -> {
                throw new RuntimeException("branch 1 failed");
            });
            parallel.branch("branch-2", String.class, branch -> "never");
        }
        ParallelResult result = parallel.get();

        var out = new LinkedHashMap<String, Object>();
        out.put("completionReason", result.completionStatus().name());
        out.put("status", result.failed() > 0 ? "FAILED" : "SUCCEEDED");
        out.put("successCount", result.succeeded());
        out.put("failureCount", result.failed());
        out.put("totalCount", result.succeeded() + result.failed());
        return out;
    }
}
