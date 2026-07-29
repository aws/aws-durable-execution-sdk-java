// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagResult;

/**
 * 10-17: the untested intersection of nesting and large payloads — a nested DAG whose OWN aggregate is offloaded, then
 * replayed across a suspend.
 *
 * <p>Modeled on 10-15 / {@link DagLargePayload}: the {@code wait} sits OUTSIDE the outer DAG so the DAG completes in the
 * first invocation and the NEXT invocation replays BOTH completed (offloaded) containers.
 *
 * <p>Structure:
 *
 * <ol>
 *   <li>Outer container {@code outernested} ({@code maxConcurrency=1}) has exactly ONE task: {@code inner} — a nested
 *       dag task, itself {@code maxConcurrency=1}, with six step tasks {@code p1..p6}, each returning a single distinct
 *       letter repeated 51200 times ({@code p1}="a"×51200 .. {@code p6}="f"×51200). Six × 51200 = 307200 chars ≈ 307KB,
 *       comfortably over the 256KB checkpoint limit, so the inner aggregate offloads — and because the outer embeds the
 *       inner result in full, the outer offloads too.
 *   <li>At HANDLER level, after the DAG returns: {@code digestBefore} — a checkpointed step that reads the inner
 *       {@link DagResult} and computes a compact digest {@code "<innerTaskCount>:<totalInnerLength>:
 *       <firstCharOfEachInnerResultInOrder>"} → {@code "6:307200:abcdef"}. As a checkpointed step it survives the
 *       suspend.
 *   <li>An outer 2s {@code wait} that ends the invocation, forcing the next one to replay both completed containers.
 *   <li>{@code digestAfter} — recomputes the identical digest from the REPLAYED inner result after the resume.
 * </ol>
 *
 * <p>The decisive, language-neutral assertion is {@code digestBefore == digestAfter == "6:307200:abcdef"} with
 * {@code match: true}, {@code innerReason: ALL_COMPLETED} and {@code innerCounts: [6,0,0,6]}. That proves the inner
 * per-task detail survived the offload of BOTH containers. Under the bug the inner would come back empty, so the digest
 * after replay would differ — {@code innerReason} would still read {@code ALL_COMPLETED} from a fabricated result,
 * which is exactly why the digest, not the reason, is the decisive check. Outcome-only — no history/event count is
 * pinned until MEASURED from a real cloud run (see 10-15 / DagLargePayload). See NESTED_OFFLOAD_CONTRACT / 10-17.yaml.
 */
public class DagNestedLargePayload extends DurableHandler<Object, Map<String, Object>> {

    /**
     * Each inner task returns this many repetitions of its own letter — under the per-op limit, over it in aggregate.
     */
    private static final int PER_TASK = 51200;

    /** Six inner tasks p1..p6 → letters a..f; inner aggregate = 6 × 51200 = 307200 chars ≈ 307KB > 256KB. */
    private static final int INNER_TASK_COUNT = 6;

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        // Outer container holds exactly ONE task: the nested `inner` dag. The wait lives at HANDLER level (below), so
        // the outer DAG completes in the first invocation and the next one replays both completed containers.
        DagResult r = context.dag(
                "outernested",
                d -> d.dag(
                        "inner",
                        nd -> {
                            for (int i = 1; i <= INNER_TASK_COUNT; i++) {
                                final String letter = String.valueOf((char) ('a' + (i - 1)));
                                nd.step("p" + i, String.class, (deps, s) -> letter.repeat(PER_TASK));
                            }
                        },
                        DagConfig.builder().maxConcurrency(1).build()),
                DagConfig.builder().maxConcurrency(1).build());

        // Digest of the inner aggregate BEFORE the suspend. A checkpointed step, so it survives the wait; on the resume
        // it fast-paths from its checkpoint, carrying the pre-suspend value forward for comparison.
        String digestBefore =
                context.step("digestBefore", String.class, s -> innerDigest((DagResult)
                        r.getResult("inner").orElseThrow()));

        // The whole point: end this invocation so the next one replays both completed (offloaded) containers.
        context.wait("settle", Duration.ofSeconds(2));

        // Recomputed from the REPLAYED inner DagResult after the resume. Equality with digestBefore proves the inner
        // per-task detail round-tripped intact through the offload of both containers.
        DagResult inner = (DagResult) r.getResult("inner").orElseThrow();
        String digestAfter = innerDigest(inner);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("innerReason", inner.completionReason().name());
        // innerCounts = [total, failed, skipped, succeeded] — the aggregate the inner offloaded envelope carries.
        out.put(
                "innerCounts",
                List.of(inner.totalCount(), inner.failureCount(), inner.skippedCount(), inner.successCount()));
        out.put("digestBefore", digestBefore);
        out.put("digestAfter", digestAfter);
        out.put("match", digestBefore.equals(digestAfter));
        return out;
    }

    /**
     * Language-neutral digest of the inner aggregate:
     * {@code "<innerTaskCount>:<totalLength>:<firstCharOfEachTaskInOrder>"}. For this graph it is exactly
     * {@code "6:307200:abcdef"}. Never returns the payloads themselves, keeping the digest small.
     */
    private static String innerDigest(DagResult inner) {
        long totalLength = 0;
        StringBuilder firstChars = new StringBuilder();
        for (int i = 1; i <= INNER_TASK_COUNT; i++) {
            String v = (String) inner.getResult("p" + i).orElseThrow();
            totalLength += v.length();
            firstChars.append(v.charAt(0));
        }
        return inner.totalCount() + ":" + totalLength + ":" + firstChars;
    }
}
