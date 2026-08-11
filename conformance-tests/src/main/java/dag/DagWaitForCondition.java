// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import static software.amazon.lambda.durable.operation.DurableDagOperation.dag;

import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.model.WaitForConditionResult;

/**
 * 10-8: DAG task that is a waitForCondition (flat WaitForCondition op under the DAG).
 *
 * <p>poll(waitForCondition from 0, +1 per poll, stops when state reaches 2 → 2) -&gt; done(step[poll]=poll*5=10). The
 * wait suspends and resumes the whole invocation, proving the DAG drains across the suspend/resume boundary.
 * maxConcurrency=1. Every task succeeds → ALL_COMPLETED. Returns the canonical summary from 10-8.yaml.
 */
public class DagWaitForCondition extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        DagResult r = dag(
                "wfcdag",
                d -> {
                    var poll = d.waitForCondition(
                            "poll",
                            Integer.class,
                            (deps, state, ctx) -> {
                                int next = state + 1;
                                return next >= 2
                                        ? WaitForConditionResult.stopPolling(next)
                                        : WaitForConditionResult.continuePolling(next);
                            },
                            WaitForConditionConfig.<Integer>builder()
                                    .initialState(0)
                                    .build());
                    d.step("done", Integer.class, (deps, s) -> deps.get(poll).orElseThrow() * 5)
                            .reads(poll);
                },
                DagConfig.builder().maxConcurrency(1).build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("statuses", DagSummary.statuses(r));
        out.put("counts", DagSummary.counts(r));
        out.put("poll", r.getResult("poll").orElseThrow());
        out.put("done", r.getResult("done").orElseThrow());
        return out;
    }
}
