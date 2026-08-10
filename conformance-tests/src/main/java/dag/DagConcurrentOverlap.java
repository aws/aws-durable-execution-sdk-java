// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import static software.amazon.lambda.durable.dag.DagOperations.dag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.dag.DagResult;

/**
 * 10-13: DAG real overlap inside one invocation (maxConcurrency unset).
 *
 * <p>root(step-&gt;1) -&gt; {slow(step[root], sleeps ~2s, "S"), fast(step[root], sleeps ~200ms, "F")}; slow is
 * registered first. afterSlow(step[slow]="Ss") is registered before afterFast(step[fast]="Ff") so afterFast becomes
 * ready — and starts — FIRST, inverting registration order versus start order. merge(step[afterSlow, afterFast]) fans
 * in to "SsFf". Because tasks finish out of registration order, a counter-based ID regression would look for a
 * checkpoint that is not there and fail replay consistency, so only order-invariant outcomes are asserted (see
 * CONCURRENCY_COVERAGE_CONTRACT).
 *
 * <p>Peak concurrency is instrumented on the user-executor threads that run slow/fast via an atomic active-count and a
 * running max, returned as {@code peakConcurrency} so the scenario cannot silently become vacuous if the scheduler is
 * ever serialised.
 */
public class DagConcurrentOverlap extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        // Shared, thread-safe peak-concurrency instrumentation: slow and fast run on user-executor threads, so we
        // track the maximum simultaneously-active body count with atomics (not plain ints).
        final AtomicInteger active = new AtomicInteger(0);
        final AtomicInteger peak = new AtomicInteger(0);

        DagResult r = dag("overlapdag", d -> {
            var root = d.step("root", Integer.class, (deps, s) -> 1);
            var slow = d.step("slow", String.class, (deps, s) -> {
                        enter(active, peak);
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            active.decrementAndGet();
                        }
                        return "S";
                    })
                    .after(root);
            var fast = d.step("fast", String.class, (deps, s) -> {
                        enter(active, peak);
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            active.decrementAndGet();
                        }
                        return "F";
                    })
                    .after(root);
            var afterSlow = d.step(
                            "afterSlow",
                            String.class,
                            (deps, s) -> deps.get(slow).orElseThrow() + "s")
                    .reads(slow);
            var afterFast = d.step(
                            "afterFast",
                            String.class,
                            (deps, s) -> deps.get(fast).orElseThrow() + "f")
                    .reads(fast);
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
        out.put("peakConcurrency", peak.get());
        return out;
    }

    /** Records entry to a concurrent task body and updates the running peak (thread-safe). */
    private static void enter(AtomicInteger active, AtomicInteger peak) {
        int now = active.incrementAndGet();
        peak.accumulateAndGet(now, Math::max);
    }
}
