// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import static software.amazon.lambda.durable.operation.DurableDagOperation.dag;

import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.model.ParallelResult;

/**
 * 10-7: DAG task that is a parallel of two named branches (flat parallel container under the DAG).
 *
 * <p>fork(parallel left-&gt;"L", right-&gt;"R") -&gt; join(step[fork]="&lt;succeeded&gt;/&lt;size&gt;"="2/2").
 * maxConcurrency=1 at both the DAG and parallel levels for a deterministic history. Every task succeeds →
 * ALL_COMPLETED.
 *
 * <p>Aggregate-only join: Java cannot read parallel branch values from a step task, because {@code DurableFuture.get()}
 * calls {@code validateCurrentThreadType()} unconditionally and any durable-op read from a step body throws
 * {@code IllegalStateException}. Java's {@link ParallelResult} is aggregate-only by design (heterogeneous branch
 * types), unlike the TS {@code BatchResult}. So {@code join} reads only the {@link ParallelResult} handed to it as the
 * dep value and returns {@code "<succeeded>/<size>"}. Reading child values is covered by 10-6 (map). Returns the
 * canonical summary from 10-7.yaml.
 */
public class DagParallel extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        DagResult r = dag(
                "paralleldag",
                d -> {
                    var fork = d.parallel(
                            "fork",
                            p -> {
                                p.branch("left", String.class, ctx -> ctx.step(null, String.class, s -> "L"));
                                p.branch("right", String.class, ctx -> ctx.step(null, String.class, s -> "R"));
                            },
                            ParallelConfig.builder().maxConcurrency(1).build());
                    d.step("join", String.class, (deps, s) -> {
                                ParallelResult pr = deps.get(fork).orElseThrow();
                                return pr.succeeded() + "/" + pr.size();
                            })
                            .reads(fork);
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
