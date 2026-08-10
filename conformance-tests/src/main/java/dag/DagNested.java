// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import static software.amazon.lambda.durable.dag.DagOperations.dag;

import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagResult;

/**
 * 10-9: DAG task that is itself a nested DAG / sub-dag (flat nested Dag container under the outer DAG).
 *
 * <p>pre(step-&gt;1) -&gt; sub(nested dag[pre]: n1-&gt;2, n2[n1]=n1+3=5) -&gt; post(step[sub]=sub.n2*10=50).
 * maxConcurrency=1 at both DAG levels for a deterministic topological order. Every task succeeds → ALL_COMPLETED at
 * both levels. Returns the canonical summary from 10-9.yaml.
 */
public class DagNested extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        DagResult r = dag(
                "outerdag",
                d -> {
                    var pre = d.step("pre", Integer.class, (deps, s) -> 1);
                    var sub = d.dag("sub", nd -> {
                                var n1 = nd.step("n1", Integer.class, (deps, s) -> 2);
                                nd.step(
                                                "n2",
                                                Integer.class,
                                                (deps, s) -> deps.get(n1).orElseThrow() + 3)
                                        .reads(n1);
                            })
                            .after(pre);
                    d.step("post", Integer.class, (deps, s) -> {
                                DagResult nested = deps.get(sub).orElseThrow();
                                return ((Number) nested.getResult("n2").orElseThrow()).intValue() * 10;
                            })
                            .reads(sub);
                },
                DagConfig.builder().maxConcurrency(1).build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("statuses", DagSummary.statuses(r));
        out.put("counts", DagSummary.counts(r));
        out.put("post", r.getResult("post").orElseThrow());
        return out;
    }
}
