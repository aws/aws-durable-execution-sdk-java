// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import static software.amazon.lambda.durable.operation.DurableDagOperation.dag;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.dag.DagResult;

/**
 * 10-14: DAG inverted readiness across a suspend (maxConcurrency unset).
 *
 * <p>root(step-&gt;1) -&gt; {slow(wait 8s), fast(wait 2s)}; slow is registered first. Both waits start in the first
 * invocation, so the invocation suspends with TWO tasks in flight and resumes twice. afterSlow(step .after(slow)="S")
 * is registered before afterFast(step .after(fast)="F"), so afterFast becomes ready one invocation earlier — the
 * downstream pair starts in the reverse of registration order across different invocations. merge(step[afterSlow,
 * afterFast]) fans in to "SF". This is the replay-flip case: a counter-based ID regression cannot survive resuming into
 * a mid-DAG state with concurrent in-flight tasks. Timers (not races) decide the order, so the outcome is
 * deterministic; the ~6s gap between the waits must not shrink below ~4s. See CONCURRENCY_COVERAGE_CONTRACT.
 */
public class DagConcurrentSuspend extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        DagResult r = dag("suspenddag", d -> {
            var root = d.step("root", Integer.class, (deps, s) -> 1);
            var slow = d.wait("slow", Duration.ofSeconds(8)).after(root);
            var fast = d.wait("fast", Duration.ofSeconds(2)).after(root);
            var afterSlow = d.step("afterSlow", String.class, (deps, s) -> "S").after(slow);
            var afterFast = d.step("afterFast", String.class, (deps, s) -> "F").after(fast);
            d.step(
                            "merge",
                            String.class,
                            (deps, s) -> deps.get(afterSlow).orElseThrow()
                                    + deps.get(afterFast).orElseThrow())
                    .reads(afterSlow, afterFast);
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("statuses", DagSummary.statuses(r));
        out.put("counts", DagSummary.counts(r));
        out.put("merge", r.getResult("merge").orElseThrow());
        return out;
    }
}
