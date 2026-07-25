// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagResult;

/**
 * 10-4: DAG in-graph Wait task (suspend and resume).
 *
 * <p>start -&gt; pause(Wait 5s) -&gt; finish. pause suspends the whole invocation until the wait elapses, then resumes
 * in a fresh invocation. finish returns "resumed", proving the DAG ran across the suspend/resume boundary. Returns the
 * canonical summary from 10-4.yaml.
 */
public class DagWaitResume extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        DagResult r = context.dag(
                "waitresume",
                d -> {
                    var start = d.step("start", String.class, (deps, s) -> "started");
                    var pause = d.wait("pause", Duration.ofSeconds(5)).after(start);
                    d.step("finish", String.class, (deps, s) -> "resumed").after(pause);
                },
                DagConfig.builder().maxConcurrency(1).build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("statuses", DagSummary.statuses(r));
        out.put("counts", DagSummary.counts(r));
        out.put("marker", r.getResult("finish").orElseThrow());
        return out;
    }
}
