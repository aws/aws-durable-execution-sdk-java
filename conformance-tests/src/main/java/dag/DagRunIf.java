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
import software.amazon.lambda.durable.dag.TaskStatus;

/**
 * 10-3: DAG per-task conditional execution (runIf).
 *
 * <p>classify returns "review". publish/review/block each depend on classify and are guarded by a runIf predicate that
 * runs the branch only when classify's result equals the branch's own name. Only review runs; publish and block are
 * SKIPPED and emit no events. Returns the canonical summary from 10-3.yaml.
 */
public class DagRunIf extends DurableHandler<Object, Map<String, Object>> {

    private static final String[] BRANCHES = {"publish", "review", "block"};

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        DagResult r = dag(
                "runif",
                d -> {
                    var classify = d.step("classify", String.class, (deps, s) -> "review");
                    for (String branch : BRANCHES) {
                        final String name = branch;
                        d.step(name, String.class, (deps, s) -> name)
                                .reads(classify)
                                .runIf(deps -> name.equals(deps.get(classify).orElse(null)));
                    }
                },
                DagConfig.builder().maxConcurrency(1).build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("statuses", DagSummary.statuses(r));
        out.put("counts", DagSummary.counts(r));
        String branch = null;
        for (String b : BRANCHES) {
            if (r.getStatus(b).orElse(null) == TaskStatus.SUCCEEDED) {
                branch = b;
                break;
            }
        }
        out.put("branch", branch);
        return out;
    }
}
