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
import software.amazon.lambda.durable.dag.TriggerRule;

/**
 * 10-2: DAG trigger-rule compensation (COMPLETED_WITH_FAILURES).
 *
 * <p>charge (root) always fails. fulfill uses the default ALL_SUCCESS trigger, so it is SKIPPED. refund uses ALL_FAILED
 * and runs ("refunded"). audit uses ALL_DONE and runs ("logged"). charge exhausts the default retry policy before
 * failing terminally, so the DAG drains to COMPLETED_WITH_FAILURES without throwing. Returns the canonical summary from
 * 10-2.yaml.
 */
public class DagCompensation extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        DagResult r = dag(
                "compensation",
                d -> {
                    var charge = d.step("charge", String.class, (deps, s) -> {
                        throw new RuntimeException("payment declined");
                    });
                    d.step("fulfill", String.class, (deps, s) -> "fulfilled").after(charge);
                    d.step("refund", String.class, (deps, s) -> "refunded")
                            .after(charge)
                            .triggerRule(TriggerRule.ALL_FAILED);
                    d.step("audit", String.class, (deps, s) -> "logged")
                            .after(charge)
                            .triggerRule(TriggerRule.ALL_DONE);
                },
                DagConfig.builder().maxConcurrency(1).build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("statuses", DagSummary.statuses(r));
        out.put("counts", DagSummary.counts(r));
        return out;
    }
}
