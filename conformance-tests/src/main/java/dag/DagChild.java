// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagResult;

/**
 * 10-5: DAG task that is a runInChildContext (flat child container under the DAG).
 *
 * <p>seed(step-&gt;1) -&gt; group(runInChildContext[seed]: inner-a=seed+1=2, inner-b=seed+2=3, returns 5) -&gt;
 * done(step[group]=group*2=10). maxConcurrency=1 for a deterministic topological order. Every task succeeds →
 * ALL_COMPLETED. Returns the canonical summary from 10-5.yaml.
 */
public class DagChild extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        DagResult r = context.dag(
                "childdag",
                d -> {
                    var seed = d.step("seed", Integer.class, (deps, s) -> 1);
                    var group = d.runInChildContext("group", Integer.class, (deps, childCtx) -> {
                                int seedVal = deps.get(seed);
                                int a = childCtx.step("inner-a", Integer.class, s -> seedVal + 1);
                                int b = childCtx.step("inner-b", Integer.class, s -> seedVal + 2);
                                return a + b;
                            })
                            .reads(seed);
                    d.step("done", Integer.class, (deps, s) -> deps.get(group) * 2).reads(group);
                },
                DagConfig.builder().maxConcurrency(1).build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("statuses", DagSummary.statuses(r));
        out.put("counts", DagSummary.counts(r));
        out.put("group", r.getResult("group").orElseThrow());
        out.put("done", r.getResult("done").orElseThrow());
        return out;
    }
}
