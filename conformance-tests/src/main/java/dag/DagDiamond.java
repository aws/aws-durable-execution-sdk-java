// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import static software.amazon.lambda.durable.operation.DurableDagOperation.dag;

import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagResult;

/**
 * 10-1: DAG diamond fan-out/fan-in (all tasks complete).
 *
 * <p>fetch(10) -&gt; {ta(=fetch+1=11), tb(=fetch*2=20)} -&gt; merge(=ta+tb=31). maxConcurrency=1 for a deterministic
 * topological order. Every task succeeds → ALL_COMPLETED. Returns the canonical summary from 10-1.yaml.
 */
public class DagDiamond extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        DagResult r = dag(
                "diamond",
                d -> {
                    var fetch = d.step("fetch", Integer.class, (deps, s) -> 10);
                    var ta = d.step(
                                    "ta",
                                    Integer.class,
                                    (deps, s) -> deps.get(fetch).orElseThrow() + 1)
                            .reads(fetch);
                    var tb = d.step(
                                    "tb",
                                    Integer.class,
                                    (deps, s) -> deps.get(fetch).orElseThrow() * 2)
                            .reads(fetch);
                    d.step(
                                    "merge",
                                    Integer.class,
                                    (deps, s) -> deps.get(ta).orElseThrow()
                                            + deps.get(tb).orElseThrow())
                            .reads(ta, tb);
                },
                DagConfig.builder().maxConcurrency(1).build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("statuses", DagSummary.statuses(r));
        out.put("counts", DagSummary.counts(r));
        out.put("merge", r.getResult("merge").orElseThrow());
        return out;
    }
}
