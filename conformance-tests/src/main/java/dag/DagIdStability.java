// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import static software.amazon.lambda.durable.dag.DagOperations.dag;

import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.dag.DagResult;

/**
 * 10-20: DAG task-id stability across independently forced completion orders.
 *
 * <p>Identical shape to 10-13's overlap (DagConcurrentOverlap) &mdash; root -&gt; {a, b} -&gt; {afterA, afterB} -&gt;
 * merge, maxConcurrency unset &mdash; except which sibling sleeps longer is driven by the input's {@code swap} flag:
 * swap=false makes {@code a} finish first; swap=true makes {@code b} finish first. Both invocations register the SAME
 * task names in the SAME order every time &mdash; only the RUNTIME completion order changes.
 *
 * <p>This is the harness-level counterpart to 10-13: 10-13 proves out-of-order completion doesn't fail the execution
 * (an INDIRECT proof of name-based ids, since a counter-based scheme would trip the SDK's own replay-consistency
 * check). This scenario is invoked TWICE by a dedicated script ({@code id_stability.py}, not the normal
 * single-invocation validator) with swap flipped between runs, and asserts each task's {@code Id} field in the captured
 * execution history is IDENTICAL across both runs &mdash; the direct proof that ids are derived from the task name, not
 * from completion order or a counter.
 */
public class DagIdStability extends DurableHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, DurableContext context) {
        boolean swap = input != null && Boolean.TRUE.equals(input.get("swap"));

        DagResult r = dag("idstabilitydag", d -> {
            var root = d.step("root", Integer.class, (deps, s) -> 1);
            var a = d.step("a", String.class, (deps, s) -> {
                        sleepQuietly(swap ? 2000 : 200);
                        return "A";
                    })
                    .after(root);
            var b = d.step("b", String.class, (deps, s) -> {
                        sleepQuietly(swap ? 200 : 2000);
                        return "B";
                    })
                    .after(root);
            var afterA = d.step("afterA", String.class, (deps, s) -> deps.get(a).orElseThrow() + "a")
                    .reads(a);
            var afterB = d.step("afterB", String.class, (deps, s) -> deps.get(b).orElseThrow() + "b")
                    .reads(b);
            d.step(
                            "merge",
                            String.class,
                            (deps, s) -> deps.get(afterA).orElseThrow()
                                    + deps.get(afterB).orElseThrow())
                    .reads(afterA, afterB);
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("statuses", DagSummary.statuses(r));
        out.put("counts", DagSummary.counts(r));
        out.put("merge", r.getResult("merge").orElseThrow());
        return out;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
