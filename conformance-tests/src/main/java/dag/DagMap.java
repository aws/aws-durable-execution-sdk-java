// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.model.MapResult;

/**
 * 10-6: DAG task that is a map over a fixed item list (flat map container under the DAG).
 *
 * <p>squares(map [1,2] each item-&gt;item*item = [1,4]) -&gt; sum(step[squares]=1+4=5). maxConcurrency=1 at both the
 * DAG and map levels for a deterministic history. Every task succeeds → ALL_COMPLETED. Returns the canonical summary
 * from 10-6.yaml.
 */
public class DagMap extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        DagResult r = context.dag(
                "mapdag",
                d -> {
                    var squares = d.map(
                            "squares",
                            List.of(1, 2),
                            Integer.class,
                            (item, index, ctx) -> ctx.step(null, Integer.class, s -> item * item),
                            MapConfig.builder().maxConcurrency(1).build());
                    d.step("sum", Integer.class, (deps, s) -> {
                                MapResult<Integer> m = deps.get(squares).orElseThrow();
                                return m.results().stream()
                                        .filter(Objects::nonNull)
                                        .mapToInt(Integer::intValue)
                                        .sum();
                            })
                            .reads(squares);
                },
                DagConfig.builder().maxConcurrency(1).build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("statuses", DagSummary.statuses(r));
        out.put("counts", DagSummary.counts(r));
        out.put("sum", r.getResult("sum").orElseThrow());
        return out;
    }
}
