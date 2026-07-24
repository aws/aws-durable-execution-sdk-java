// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.dag;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.dag.TriggerRule;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * DAG example: saga-style compensation via trigger rules. {@code charge} fails; {@code refund} fires on
 * {@link TriggerRule#ALL_FAILED}; {@code fulfill} is skipped (default ALL_SUCCESS over a failed upstream); {@code audit}
 * always runs ({@link TriggerRule#ALL_DONE}). The DAG completes with {@code COMPLETED_WITH_FAILURES}. Returns a
 * pipe-delimited summary of the completion reason and per-task statuses.
 */
public class DagCompensationExample extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        var noRetry = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .build();
        DagResult r = context.dag("saga", d -> {
            var charge = d.step(
                    "charge",
                    String.class,
                    (deps, s) -> {
                        throw new RuntimeException("charge failed");
                    },
                    noRetry);
            d.step("refund", String.class, (deps, s) -> "refunded")
                    .after(charge)
                    .triggerRule(TriggerRule.ALL_FAILED);
            d.step("fulfill", String.class, (deps, s) -> "fulfilled").after(charge);
            d.step("audit", String.class, (deps, s) -> "audited")
                    .after(charge)
                    .triggerRule(TriggerRule.ALL_DONE);
        });
        return r.completionReason().name()
                + "|" + r.getStatus("charge").map(Enum::name).orElse("?")
                + "|" + r.getStatus("refund").map(Enum::name).orElse("?")
                + "|" + r.getStatus("fulfill").map(Enum::name).orElse("?")
                + "|" + r.getStatus("audit").map(Enum::name).orElse("?");
    }
}
