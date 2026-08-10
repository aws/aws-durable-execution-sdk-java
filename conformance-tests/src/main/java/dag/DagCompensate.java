// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import static software.amazon.lambda.durable.dag.DagOperations.dag;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.dag.TriggerRule;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * 10-18: compensation dependency read on a FAILED upstream is ABSENT, not present (the deps-nullability contract).
 *
 * <p>DAG "compensate" with two step tasks: charge -&gt; audit. {@code charge} is a root step that ALWAYS fails; its
 * retry strategy is disabled (a single attempt) so it ends FAILED deterministically (exactly one StepFailed).
 * {@code audit} depends on {@code charge} via an INLINE (typed) dependency ({@code .reads(charge)}) and uses the
 * ALL_DONE trigger rule, so it runs even though {@code charge} FAILED and receives {@code charge} in its resolved deps.
 * Its body reads {@code deps.get(charge)}: a dependency that did not SUCCEED resolves to an empty
 * {@link java.util.Optional} (absent), never a stale/fabricated value, so {@code audit} returns {@code "absent"} when
 * the Optional is empty and {@code "present"} otherwise.
 *
 * <p>The DAG drains to COMPLETED_WITH_FAILURES without throwing: {@code charge} FAILED, {@code audit} SUCCEEDED with
 * result {@code "absent"}. Returns the canonical summary from 10-18.yaml.
 */
public class DagCompensate extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        DagResult r = dag(
                "compensate",
                d -> {
                    // Single attempt (no retry) so charge ends FAILED deterministically.
                    var charge = d.step(
                            "charge",
                            String.class,
                            (deps, s) -> {
                                throw new RuntimeException("charge failed");
                            },
                            StepConfig.builder()
                                    .retryStrategy(RetryStrategies.fixedDelay(1, Duration.ofSeconds(1)))
                                    .build());
                    // Inline dep on charge + ALL_DONE: audit runs and reads charge. A failed dependency's
                    // value is absent (Optional.empty()), so audit returns "absent".
                    d.step("audit", String.class, (deps, s) -> deps.get(charge).isEmpty() ? "absent" : "present")
                            .reads(charge)
                            .triggerRule(TriggerRule.ALL_DONE);
                },
                DagConfig.builder().maxConcurrency(1).build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("statuses", DagSummary.statuses(r));
        out.put("counts", DagSummary.counts(r));
        out.put("audit", r.getResult("audit").orElseThrow());
        return out;
    }
}
