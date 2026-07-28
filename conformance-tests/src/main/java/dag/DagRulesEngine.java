// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.dag.DagCompletionConfig;
import software.amazon.lambda.durable.dag.DagCompletionDecision;
import software.amazon.lambda.durable.dag.DagCompletionOutcome;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.dag.TaskStatus;

/**
 * 10-19: DAG custom result-based completion. A rules-engine predicate short-circuits the moment any task's SUCCEEDED
 * result carries a REJECT verdict -- expressible only because the custom-completion predicate can inspect task
 * RESULTS, not just aggregate counts.
 *
 * <p>DAG "rulesengine" with max-concurrency 1 and a linear chain of three step tasks: r1 -&gt; r2 -&gt; r3, each
 * returning a verdict map. r1 -&gt; ACCEPT, r2 -&gt; REJECT, r3 (never runs) -&gt; ACCEPT.
 *
 * <p>The completion config is {@link DagCompletionConfig#custom}, not a threshold: after every settlement it
 * receives a live progress snapshot and inspects every SUCCEEDED item's result for a REJECT verdict. The moment it
 * sees one, it returns a FAILED completion decision. r3 is never started and is absent from the results map. The DAG
 * completes with {@code CUSTOM_COMPLETION_FAILED} -- distinct from {@code COMPLETED_WITH_FAILURES}, since no
 * individual task FAILED. {@code throwIfError()} MUST still throw in this case (the contract keys off
 * {@code completionReason} too, not {@code failureCount} alone).
 *
 * <p>Returns the canonical summary from 10-19.yaml.
 */
public class DagRulesEngine extends DurableHandler<Object, Map<String, Object>> {

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        DagConfig config = DagConfig.builder()
                .maxConcurrency(1)
                .completionConfig(DagCompletionConfig.custom(status -> {
                    boolean anyRejected = status.items().stream()
                            .anyMatch(item -> item.status().isPresent()
                                    && item.status().get() == TaskStatus.SUCCEEDED
                                    && item.result().isPresent()
                                    && "REJECT".equals(((Map<String, Object>) item.result().get()).get("verdict")));
                    return anyRejected
                            ? DagCompletionDecision.complete(DagCompletionOutcome.FAILED)
                            : DagCompletionDecision.continueDag();
                }))
                .build();

        DagResult r = context.dag(
                "rulesengine",
                d -> {
                    var r1 = d.step("r1", Map.class, (deps, s) -> Map.of("verdict", "ACCEPT"));
                    var r2 = d.step("r2", Map.class, (deps, s) -> Map.of("verdict", "REJECT"))
                            .reads(r1);
                    d.step("r3", Map.class, (deps, s) -> Map.of("verdict", "ACCEPT")).reads(r2);
                },
                config);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("counts", DagSummary.counts(r));
        out.put("r1", r.getResult("r1").orElseThrow());
        out.put("r2", r.getResult("r2").orElseThrow());
        return out;
    }
}
