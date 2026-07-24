// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.dag.TaskStatus;
import software.amazon.lambda.durable.dag.TriggerRule;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/** End-to-end DAG tests via the local runner. */
class DagIntegrationTest {

    @Test
    void diamondResolvesWithTypedDeps() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            DagResult r = ctx.dag("etl", d -> {
                var a = d.step("a", String.class, (deps, s) -> "A");
                var b = d.step("b", String.class, (deps, s) -> deps.get(a) + "B").reads(a);
                var c = d.step("c", String.class, (deps, s) -> deps.get(a) + "C").reads(a);
                d.step("dd", String.class, (deps, s) -> deps.get(b) + deps.get(c)).reads(b, c);
            });
            return (String) r.getResult("dd").orElse("MISSING");
        });

        var result = runner.runUntilComplete("go");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("ABAC", result.getResult(String.class));
    }

    @Test
    void runIfSkipCascades() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            DagResult r = ctx.dag("cond", d -> {
                var gate = d.step("gate", Integer.class, (deps, s) -> 0);
                var maybe = d.step("maybe", String.class, (deps, s) -> "ran")
                        .reads(gate)
                        .runIf(deps -> ((Integer) deps.get(gate)) > 0);
                d.step("after", String.class, (deps, s) -> "after").dependsOn(maybe);
            });
            return r.getStatus("maybe").map(Enum::name).orElse("?")
                    + "|"
                    + r.getStatus("after").map(Enum::name).orElse("?");
        });

        var result = runner.runUntilComplete("go");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        // maybe skipped (runIf false); after has ALL_SUCCESS default over a SKIPPED upstream -> skipped too
        assertEquals(TaskStatus.SKIPPED.name() + "|" + TaskStatus.SKIPPED.name(), result.getResult(String.class));
    }

    @Test
    void failureDrainsWithCompensation() {
        var noRetry = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .build();
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            DagResult r = ctx.dag("saga", d -> {
                var charge = d.step(
                        "charge",
                        String.class,
                        (deps, s) -> {
                            throw new RuntimeException("charge failed");
                        },
                        noRetry);
                d.step("refund", String.class, (deps, s) -> "refunded")
                        .dependsOn(charge)
                        .triggerRule(TriggerRule.ALL_FAILED);
                d.step("fulfill", String.class, (deps, s) -> "fulfilled").dependsOn(charge);
                d.step("audit", String.class, (deps, s) -> "audited")
                        .dependsOn(charge)
                        .triggerRule(TriggerRule.ALL_DONE);
            });
            return r.completionReason().name()
                    + "|" + r.getStatus("charge").map(Enum::name).orElse("?")
                    + "|" + r.getStatus("refund").map(Enum::name).orElse("?")
                    + "|" + r.getStatus("fulfill").map(Enum::name).orElse("?")
                    + "|" + r.getStatus("audit").map(Enum::name).orElse("?");
        });

        var result = runner.runUntilComplete("go");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(
                "COMPLETED_WITH_FAILURES|FAILED|SUCCEEDED|SKIPPED|SUCCEEDED", result.getResult(String.class));
    }
}
