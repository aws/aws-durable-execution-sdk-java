// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.dag.DagCompletionConfig;
import software.amazon.lambda.durable.dag.DagCompletionReason;
import software.amazon.lambda.durable.dag.DagConfig;
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
                var b = d.step("b", String.class, (deps, s) -> deps.get(a) + "B")
                        .reads(a);
                var c = d.step("c", String.class, (deps, s) -> deps.get(a) + "C")
                        .reads(a);
                d.step("dd", String.class, (deps, s) -> deps.get(b) + deps.get(c))
                        .reads(b, c);
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
        assertEquals("COMPLETED_WITH_FAILURES|FAILED|SUCCEEDED|SKIPPED|SUCCEEDED", result.getResult(String.class));
    }

    @Test
    void replayAfterWaitDoesNotReexecuteCompletedTasks() {
        var executions = new java.util.concurrent.atomic.AtomicInteger(0);
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            DagResult r = ctx.dag("with_wait", d -> {
                var a = d.step("a", String.class, (deps, s) -> {
                    executions.incrementAndGet();
                    return "A";
                });
                var w = d.wait("w", java.time.Duration.ofMinutes(5)).dependsOn(a);
                d.step("b", String.class, (deps, s) -> deps.get(a) + "B")
                        .reads(a)
                        .dependsOn(w);
            });
            return (String) r.getResult("b").orElse("MISSING");
        });

        var result = runner.runUntilComplete("go");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("AB", result.getResult(String.class));
        // Step "a" ran exactly once despite the wait-induced suspension/replay (name-based ID fast-path).
        assertEquals(1, executions.get());
    }

    @Test
    void emptyDagCompletesImmediately() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            DagResult r = ctx.dag("empty", d -> {});
            return r.totalCount() + "|" + r.completionReason().name();
        });

        var result = runner.runUntilComplete("go");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("0|" + DagCompletionReason.ALL_COMPLETED.name(), result.getResult(String.class));
    }

    @Test
    void nestedDagScopeIsolation() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            DagResult r = ctx.dag("outer", d -> {
                var root = d.step("root", String.class, (deps, s) -> "R");
                d.dag("inner", inner -> {
                            var x = inner.step("x", String.class, (deps, s) -> "X");
                            inner.step("y", String.class, (deps, s) -> deps.get(x) + "Y")
                                    .reads(x);
                        })
                        .dependsOn(root);
            });
            DagResult innerDag = (DagResult) r.getResult("inner").orElseThrow();
            return innerDag.getResult("y").map(Object::toString).orElse("MISSING") + "|"
                    + innerDag.completionReason().name();
        });

        var result = runner.runUntilComplete("go");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("XY|" + DagCompletionReason.ALL_COMPLETED.name(), result.getResult(String.class));
    }

    @Test
    void minSuccessfulTriggersEarlyCompletion() {
        var config = DagConfig.builder()
                .completionConfig(DagCompletionConfig.minSuccessful(1))
                .build();
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            DagResult r = ctx.dag(
                    "early",
                    d -> {
                        d.step("a", String.class, (deps, s) -> "A");
                        d.step("b", String.class, (deps, s) -> "B");
                        d.step("c", String.class, (deps, s) -> "C");
                    },
                    config);
            return r.completionReason().name() + "|" + r.successCount();
        });

        var result = runner.runUntilComplete("go");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        // First success reaches the threshold; reason is MIN_SUCCESSFUL_REACHED with >= 1 success recorded.
        assertEquals(DagCompletionReason.MIN_SUCCESSFUL_REACHED.name() + "|1", result.getResult(String.class));
    }

    @Test
    void toleratedFailureCountExceededTriggersEarlyCompletion() {
        var noRetry = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .build();
        var config = DagConfig.builder()
                .completionConfig(DagCompletionConfig.toleratedFailureCount(0))
                .build();
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            DagResult r = ctx.dag(
                    "failfast",
                    d -> {
                        d.step(
                                "boom",
                                String.class,
                                (deps, s) -> {
                                    throw new RuntimeException("kaboom");
                                },
                                noRetry);
                    },
                    config);
            return r.completionReason().name() + "|" + r.failureCount();
        });

        var result = runner.runUntilComplete("go");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(DagCompletionReason.FAILURE_TOLERANCE_EXCEEDED.name() + "|1", result.getResult(String.class));
    }

    @Test
    void maxConcurrencyThrottlesConcurrentTasks() {
        var active = new java.util.concurrent.atomic.AtomicInteger(0);
        var maxObserved = new java.util.concurrent.atomic.AtomicInteger(0);
        var config = DagConfig.builder().maxConcurrency(2).build();
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            DagResult r = ctx.dag(
                    "throttle",
                    d -> {
                        for (int i = 0; i < 4; i++) {
                            d.step("t" + i, String.class, (deps, s) -> {
                                int now = active.incrementAndGet();
                                maxObserved.accumulateAndGet(now, Math::max);
                                try {
                                    Thread.sleep(50);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                active.decrementAndGet();
                                return "ok";
                            });
                        }
                    },
                    config);
            return r.successCount() + "|" + maxObserved.get();
        });

        var result = runner.runUntilComplete("go");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        // All four tasks succeed, and observed concurrency never exceeds the cap of 2.
        String[] parts = result.getResult(String.class).split("\\|");
        assertEquals(4, Integer.parseInt(parts[0]));
        int observed = Integer.parseInt(parts[1]);
        org.junit.jupiter.api.Assertions.assertTrue(
                observed >= 1 && observed <= 2, "observed concurrency must be within [1,2] but was " + observed);
    }

    @Test
    void diamondWithWaitReplaysDeterministically() {
        var aRuns = new java.util.concurrent.atomic.AtomicInteger(0);
        var bRuns = new java.util.concurrent.atomic.AtomicInteger(0);
        var cRuns = new java.util.concurrent.atomic.AtomicInteger(0);
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            DagResult r = ctx.dag("diamond", d -> {
                var a = d.step("a", String.class, (deps, s) -> {
                    aRuns.incrementAndGet();
                    return "A";
                });
                var b = d.step("b", String.class, (deps, s) -> {
                            bRuns.incrementAndGet();
                            return deps.get(a) + "B";
                        })
                        .reads(a);
                var c = d.step("c", String.class, (deps, s) -> {
                            cRuns.incrementAndGet();
                            return deps.get(a) + "C";
                        })
                        .reads(a);
                // Wait after the concurrent fan-out forces a suspend/replay before the join runs.
                var w = d.wait("w", java.time.Duration.ofMinutes(5)).dependsOn(b, c);
                d.step("join", String.class, (deps, s) -> deps.get(b) + deps.get(c))
                        .reads(b, c)
                        .dependsOn(w);
            });
            return (String) r.getResult("join").orElse("MISSING");
        });

        var result = runner.runUntilComplete("go");
        // No NonDeterministicExecutionException despite concurrent B/C completing in arbitrary order across
        // the replay boundary — name-based IDs make the join deterministic.
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("ABAC", result.getResult(String.class));
        // Each upstream ran exactly once; the post-wait replay hit their name-based fast-path.
        assertEquals(1, aRuns.get());
        assertEquals(1, bRuns.get());
        assertEquals(1, cRuns.get());
    }

    @Test
    void largeDagResultReExecutesOnReplayWithoutRerunningTasks() {
        int size = 300 * 1024; // > 256KB LARGE_RESULT_THRESHOLD for the DAG's child-context aggregate
        var bigRuns = new java.util.concurrent.atomic.AtomicInteger(0);
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            DagResult r = ctx.dag("big", d -> {
                d.step("payload", String.class, (deps, s) -> {
                    bigRuns.incrementAndGet();
                    return "x".repeat(size);
                });
            });
            int len = ((String) r.getResult("payload").orElse("")).length();
            // Wait AFTER the DAG completes forces the completed (large) DAG child to be replayed: its aggregate
            // was checkpointed as an empty payload + replayChildren=true, so on resume the child body re-runs
            // the scheduler and each task returns via its per-task checkpoint fast-path (no body re-execution).
            ctx.wait("after", java.time.Duration.ofMinutes(5));
            return len + "|" + bigRuns.get();
        });

        var result = runner.runUntilComplete("go");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        // Aggregate reconstructed to full size, and the task body executed exactly once across the replay.
        assertEquals(size + "|1", result.getResult(String.class));
    }
}
