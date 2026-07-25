// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagResult;

/**
 * 10-7: DAG task that is a parallel of two named branches (flat parallel container under the DAG).
 *
 * <p>fork(parallel left-&gt;"L", right-&gt;"R") -&gt; join(step[fork]=results joined by "-" = "L-R"). maxConcurrency=1
 * at both the DAG and parallel levels for a deterministic history. Every task succeeds → ALL_COMPLETED.
 *
 * <p>Unlike the JS SDK (whose parallel task result is a {@code BatchResult} carrying the branch values), the Java
 * {@code ParallelResult} carries only aggregate counts/statuses. The idiomatic Java way to read branch values (see
 * {@code ParallelNamedBranches}) is to capture the branch {@link DurableFuture}s; {@code join} runs after {@code fork}
 * so they are resolved. This is a handler-level alignment only — no SDK change. Returns the summary from 10-7.yaml.
 */
public class DagParallel extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        List<DurableFuture<String>> branches = new ArrayList<>();
        DagResult r = context.dag(
                "paralleldag",
                d -> {
                    var fork = d.parallel(
                            "fork",
                            p -> {
                                branches.add(p.branch("left", String.class, ctx -> ctx.step(null, String.class, s -> "L")));
                                branches.add(
                                        p.branch("right", String.class, ctx -> ctx.step(null, String.class, s -> "R")));
                            },
                            ParallelConfig.builder().maxConcurrency(1).build());
                    d.step(
                                    "join",
                                    String.class,
                                    (deps, s) -> branches.stream()
                                            .map(DurableFuture::get)
                                            .collect(Collectors.joining("-")))
                            .after(fork);
                },
                DagConfig.builder().maxConcurrency(1).build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("statuses", DagSummary.statuses(r));
        out.put("counts", DagSummary.counts(r));
        out.put("join", r.getResult("join").orElseThrow());
        return out;
    }
}
