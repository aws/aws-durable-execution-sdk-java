// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.dag.DagCompletionConfig;
import software.amazon.lambda.durable.dag.DagCompletionReason;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagPredicateException;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.dag.TaskStatus;
import software.amazon.lambda.durable.dag.TriggerRule;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/** End-to-end DAG tests via the local runner. */
class DagIntegrationTest {

    @Test
    void throwingRunIfAbortsDagAndCallerCatchesTypedExceptionWithCause() {
        // A throwing runIf ABORTS the DAG with a typed DagPredicateException (contract H5), rather than recording the
        // task FAILED or SKIPPED. The dag(...) caller can catch the typed exception; its message and taskName name the
        // offending task and its cause carries the original error. (This is the exception the caller observes after it
        // crosses the DAG child-context boundary: it is checkpointed and reconstructed from its serialized form.)
        var caught = new java.util.concurrent.atomic.AtomicReference<DagPredicateException>();
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            try {
                ctx.dag("cond", d -> {
                    var gate = d.step("gate", Integer.class, (deps, s) -> 7);
                    d.step("maybe", String.class, (deps, s) -> "ran")
                            .reads(gate)
                            .runIf(deps -> {
                                throw new IllegalStateException("predicate boom");
                            });
                });
                return "no-throw";
            } catch (DagPredicateException e) {
                caught.set(e);
                return "caught";
            }
        });

        var result = runner.runUntilComplete("go");
        // The caller handled the typed exception, so the execution completes.
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("caught", result.getResult(String.class));

        // The reconstructed exception the caller observes is a typed DagPredicateException that names the offending
        // task (both via taskName() and its message) and whose cause carries the original error and its stack trace.
        DagPredicateException e = caught.get();
        org.junit.jupiter.api.Assertions.assertNotNull(e, "caller must observe a DagPredicateException");
        assertEquals("maybe", e.taskName());
        org.junit.jupiter.api.Assertions.assertTrue(
                e.getMessage().contains("maybe") && e.getMessage().contains("predicate boom"), e.getMessage());
        org.junit.jupiter.api.Assertions.assertNotNull(e.getCause(), "the original error must be retrievable as cause");
        assertEquals("predicate boom", e.getCause().getMessage());
    }

    @Test
    void throwingRunIfLeavesNoTerminalStateAndRunsNoCompensation() {
        // The abort is durable and wire-visible: the DAG container checkpoints FAILED, the offending task has NO
        // terminal state, an already-run upstream keeps its SUCCEEDED checkpoint, and a downstream ALL_FAILED
        // compensation task never runs (the defect must not drive compensation). The top-level execution fails with
        // the typed DagPredicateException naming the task and the original error.
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            DagResult r = ctx.dag("cond", d -> {
                var gate = d.step("gate", Integer.class, (deps, s) -> 7);
                var maybe = d.step("maybe", String.class, (deps, s) -> "ran")
                        .reads(gate)
                        .runIf(deps -> {
                            throw new IllegalStateException("predicate boom");
                        });
                d.step("refund", String.class, (deps, s) -> "refunded")
                        .after(maybe)
                        .triggerRule(TriggerRule.ALL_FAILED);
            });
            return "unreached:" + r.completionReason().name();
        });

        var result = runner.runUntilComplete("go");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());

        // The DAG container failed (wire-visible abort) and its checkpoint carries the typed error intact: type is
        // DagPredicateException, message names the offending task and the original error, and the serialized cause
        // chain preserves the original error. This is the durable, history-visible record of the abort.
        var container = result.getOperation("cond");
        assertEquals(OperationStatus.FAILED, container.getStatus());
        var error = container.getContextDetails().error();
        org.junit.jupiter.api.Assertions.assertNotNull(error, "DAG container must checkpoint the failure error");
        assertEquals("software.amazon.lambda.durable.dag.DagPredicateException", error.errorType());
        org.junit.jupiter.api.Assertions.assertTrue(
                error.errorMessage().contains("maybe"),
                "message must name the offending task: " + error.errorMessage());
        org.junit.jupiter.api.Assertions.assertTrue(
                error.errorMessage().contains("IllegalStateException")
                        && error.errorMessage().contains("predicate boom"),
                "message must identify the original error: " + error.errorMessage());
        // The serialized cause chain preserves the original error (retrievable as the cause).
        org.junit.jupiter.api.Assertions.assertTrue(
                error.errorData() != null && error.errorData().contains("predicate boom"),
                "errorData must carry the original cause");

        // The already-run upstream kept its terminal SUCCEEDED state ...
        assertEquals(OperationStatus.SUCCEEDED, result.getOperation("gate").getStatus());
        // ... the offending task has NO terminal state (it was never launched) ...
        org.junit.jupiter.api.Assertions.assertNull(
                result.getOperation("maybe"), "offending task must have no terminal state");
        // ... and the downstream ALL_FAILED compensation never ran.
        org.junit.jupiter.api.Assertions.assertNull(
                result.getOperation("refund"), "downstream ALL_FAILED compensation must not run");
    }

    @Test
    void positionalArityTypedDepsSugarResolves() {
        // C9: the 1..3-arity sugar passes upstream results directly (typed via the handle) and desugars to
        // step(...).reads(...); dependency wiring is identical to the .reads() + Deps.get() form.
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            DagResult r = ctx.dag("sugar", d -> {
                var a = d.step("a", Integer.class, (deps, s) -> 1);
                var b = d.step("b", Integer.class, a, (Integer av, StepContext s) -> av + 1); // 1-arity
                var c = d.step("c", Integer.class, a, b, (Integer av, Integer bv, StepContext s) -> av + bv); // 2-arity
                d.step(
                        "dd",
                        String.class,
                        a,
                        b,
                        c,
                        (Integer av, Integer bv, Integer cv, StepContext s) -> av + "-" + bv + "-" + cv); // 3-arity
            });
            return (String) r.getResult("dd").orElse("MISSING") + "|"
                    + r.getResult("c").map(Object::toString).orElse("?");
        });

        var result = runner.runUntilComplete("go");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        // a=1, b=2, c=3, dd="1-2-3"
        assertEquals("1-2-3|3", result.getResult(String.class));
    }

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
                d.step("after", String.class, (deps, s) -> "after").after(maybe);
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
                var w = d.wait("w", java.time.Duration.ofMinutes(5)).after(a);
                d.step("b", String.class, (deps, s) -> deps.get(a) + "B")
                        .reads(a)
                        .after(w);
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
                        .after(root);
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
                var w = d.wait("w", java.time.Duration.ofMinutes(5)).after(b, c);
                d.step("join", String.class, (deps, s) -> deps.get(b) + deps.get(c))
                        .reads(b, c)
                        .after(w);
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

    @Test
    void wideFanOutTasksAlwaysObserveUpstreamValue() {
        // B1 regression. Many tasks read a common upstream under unbounded concurrency. Each reader repeatedly reads
        // the upstream via deps.get(...) while the scheduler thread concurrently records the OTHER readers' results
        // with results.put(...). Pre-fix, every reader shared the scheduler's live LinkedHashMap; a get() overlapping
        // a put()-induced table resize could observe a half-linked bucket and return null for the SUCCEEDED upstream —
        // a silently-wrong input indistinguishable from a legitimate non-ALL_SUCCESS null. With the immutable per-task
        // snapshot each reader sees a private, stable view and MUST always observe the real value. A raced null/wrong
        // read throws, failing that reader task, so any occurrence surfaces as failureCount > 0.
        //
        // Sensitivity: the scheduler harvests futures in launch order (blocking on each get()), so results.put(...)
        // calls happen roughly as tasks finish. Reader work therefore INCREASES with index so completions — and thus
        // the resize-inducing puts (the 13th/25th/49th insertions grow a default-capacity map) — land while the many
        // slower, later readers are still mid-loop on get(). That overlap is what makes the race observable; with
        // uniform durations the writes burst after every reader has already stopped reading and nothing overlaps.
        final int fanOut = 64; // >= 32; forces map growth/resizes (thresholds at 12/24/48 entries)
        final int readUnit = 6000; // reader i performs (i+1) * readUnit reads: staggered, increasing durations
        final int iterations = 50; // repeat so the timing-dependent race is meaningfully likely to surface

        for (int iter = 0; iter < iterations; iter++) {
            var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
                DagResult r = ctx.dag("fanout", d -> {
                    var up = d.step("up", String.class, (deps, s) -> "UPSTREAM");
                    for (int i = 0; i < fanOut; i++) {
                        final int reads = (i + 1) * readUnit;
                        d.step("t" + i, Boolean.class, (deps, s) -> {
                                    for (int k = 0; k < reads; k++) {
                                        Object v = deps.get(up);
                                        if (!"UPSTREAM".equals(v)) {
                                            throw new IllegalStateException(
                                                    "raced read of upstream: expected 'UPSTREAM' but observed " + v);
                                        }
                                    }
                                    return Boolean.TRUE;
                                })
                                .reads(up);
                    }
                });
                return r.successCount() + "|" + r.failureCount();
            });

            var result = runner.runUntilComplete("go");
            assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
            // upstream (1) + every fan-out reader succeed, with ZERO failures. A single raced read flips a reader to
            // FAILED and makes failureCount non-zero.
            assertEquals((fanOut + 1) + "|0", result.getResult(String.class), "iteration " + iter);
        }
    }
}
