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
 * 10-15: DAG with a large aggregate result offloaded and replayed across a suspend.
 *
 * <p>Eight step roots {@code p1..p8}, each returning 51200 repetitions of its own letter ({@code p1}="a"×51200 ..
 * {@code p8}="h"×51200). The aggregate is ~410KB, comfortably over the 256KB checkpoint threshold, while every
 * individual task result stays well under it — so only the aggregate is offloaded (checkpointed with an empty payload
 * plus the ReplayChildren flag). {@code maxConcurrency=1} keeps it deterministic.
 *
 * <p>The offload alone is not the interesting path: the reconstruct-vs-re-execute divergence between the SDKs
 * (TypeScript reconstructs from an SDK-owned {@code DagSummary} envelope; Python, Java and Go re-execute the DAG child
 * body via ReplayChildren) only fires when a <b>succeeded container is replayed</b>. So the handler suspends AFTER the
 * DAG resolves, via a 2s wait, forcing the next invocation to replay the completed container.
 *
 * <p>Flow: (1) {@code dag(...)} resolves the ~410KB aggregate; (2) a checkpointed step computes {@code digestBefore} =
 * {@code "<taskCount>:<totalLength>:<firstCharOfEachTaskInOrder>"} → {@code "8:409600:abcdefgh"} from the DagResult, so
 * it survives the suspend; (3) a 2s wait ends the invocation; (4) after the resume the same digest is recomputed from
 * the REPLAYED DagResult → {@code digestAfter}. The language-neutral assertion is {@code digestBefore == digestAfter ==
 * "8:409600:abcdefgh"} ({@code match=true}): the aggregate survived the offload and came back identical through
 * whichever replay strategy the SDK uses. Outcome-only — no history is pinned, because the container's succeeded
 * payload legitimately differs across SDKs. See LARGE_PAYLOAD_CONTRACT / 10-15.yaml.
 */
public class DagLargePayload extends DurableHandler<Object, Map<String, Object>> {

    /** Each task returns this many repetitions of its own letter — under the per-op limit, over it in aggregate. */
    private static final int PER_TASK = 51200;

    /** Eight tasks p1..p8 → letters a..h; aggregate = 8 × 51200 = 409600 chars ≈ 410KB > 256KB. */
    private static final int TASK_COUNT = 8;

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        DagResult r = context.dag(
                "bigdag",
                d -> {
                    for (int i = 1; i <= TASK_COUNT; i++) {
                        final String letter = String.valueOf((char) ('a' + (i - 1)));
                        d.step("p" + i, String.class, (deps, s) -> letter.repeat(PER_TASK));
                    }
                },
                DagConfig.builder().maxConcurrency(1).build());

        // Digest of the aggregate BEFORE the suspend. A step, so it is checkpointed and survives the wait; on the
        // resume it fast-paths from its checkpoint, carrying the pre-suspend value forward for comparison.
        String digestBefore = context.step("digestBefore", String.class, s -> digest(r));

        // The whole point: end this invocation so the next one replays the completed (offloaded) DAG container.
        context.wait("suspend", Duration.ofSeconds(2));

        // Recomputed from the REPLAYED DagResult after the resume (envelope reconstruction in JS; native child-body
        // re-execution here in Java). Equality with digestBefore proves the aggregate round-tripped intact.
        String digestAfter = digest(r);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("counts", DagSummary.counts(r));
        out.put("digestBefore", digestBefore);
        out.put("digestAfter", digestAfter);
        out.put("match", digestBefore.equals(digestAfter));
        return out;
    }

    /**
     * Language-neutral digest of the aggregate: {@code "<taskCount>:<totalLength>:<firstCharOfEachTaskInOrder>"}. For
     * this graph it is exactly {@code "8:409600:abcdefgh"}. Never returns the payload itself, keeping the summary
     * small.
     */
    private static String digest(DagResult r) {
        long totalLength = 0;
        StringBuilder firstChars = new StringBuilder();
        for (int i = 1; i <= TASK_COUNT; i++) {
            String v = (String) r.getResult("p" + i).orElseThrow();
            totalLength += v.length();
            firstChars.append(v.charAt(0));
        }
        return r.totalCount() + ":" + totalLength + ":" + firstChars;
    }
}
