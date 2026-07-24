// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.dag.DagCompletionConfig;
import software.amazon.lambda.durable.dag.DagCompletionReason;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagCyclicDependencyException;
import software.amazon.lambda.durable.dag.DagDuplicateTaskException;
import software.amazon.lambda.durable.dag.DagException;
import software.amazon.lambda.durable.dag.DagInvalidDependencyException;
import software.amazon.lambda.durable.dag.DagInvalidTaskNameException;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.dag.TaskExecution;
import software.amazon.lambda.durable.dag.TaskHandle;
import software.amazon.lambda.durable.dag.TaskStatus;
import software.amazon.lambda.durable.dag.TriggerRule;
import software.amazon.lambda.durable.dag.internal.DagContextImpl;
import software.amazon.lambda.durable.dag.internal.DagExecutor;
import software.amazon.lambda.durable.dag.internal.DagValidator;
import software.amazon.lambda.durable.dag.internal.TaskHandleImpl;
import software.amazon.lambda.durable.exception.ChildContextFailedException;
import software.amazon.lambda.durable.execution.OperationIdGenerator;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/**
 * Cross-language DAG conformance suite (Java). Implements the scenarios from {@code docs/DAG_CONFORMANCE.md} in the
 * TypeScript SDK repo against the shipped {@code feature/dag} Java API, asserts each actual outcome equals the
 * catalog's expected semantic outcome, and emits one key-sorted normalized JSON to
 * {@code /Users/parpooya/workplace/dag-conformance-out/java.json} (schema per catalog Part B).
 *
 * <p>DAG-18 (custom result-based completion) is [TS + Go ONLY] — Java has no completion predicate hook in v1 — so it is
 * NOT implemented here (Java emits 18 records).
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class DagConformanceTest {

    private static final String OUT = "/Users/parpooya/workplace/dag-conformance-out/java.json";

    /** Accumulated normalized records, keyed by scenario id (TreeMap → lexicographic key sort). */
    private static final Map<String, Object> RECORDS = new TreeMap<>();

    private static final String STEP_ERROR = "StepError";

    // ── DAG-1 ─────────────────────────────────────────────────────────────────
    @Test
    void dag1_diamond() {
        var ref = new AtomicReference<DagResult>();
        var runner = LocalDurableTestRunner.create(String.class, (in, ctx) -> {
            DagResult r = ctx.dag("dag1", d -> {
                var fetch = d.step("fetch", Integer.class, (deps, s) -> 10);
                var ta = d.step("ta", Integer.class, (deps, s) -> deps.get(fetch) + 1)
                        .reads(fetch);
                var tb = d.step("tb", Integer.class, (deps, s) -> deps.get(fetch) * 2)
                        .reads(fetch);
                d.step("merge", Integer.class, (deps, s) -> deps.get(ta) + deps.get(tb))
                        .reads(ta, tb);
            });
            ref.set(r);
            return "ok";
        });
        assertSucceeded(runner);
        DagResult r = ref.get();
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("merge").orElseThrow());
        assertEquals(31, r.getResult("merge").orElseThrow());
        assertEquals(DagCompletionReason.ALL_COMPLETED, r.completionReason());
        assertCounts(r, 4, 0, 0, 4);
        RECORDS.put("DAG-1", record("DAG-1", r, List.of("fetch", "ta", "tb", "merge")));
    }

    // ── DAG-2 ─────────────────────────────────────────────────────────────────
    @Test
    void dag2_compensationChargeFails() {
        var noRetry = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .build();
        var ref = new AtomicReference<DagResult>();
        var runner = LocalDurableTestRunner.create(String.class, (in, ctx) -> {
            DagResult r = ctx.dag("dag2", d -> {
                var charge = d.step(
                        "charge",
                        String.class,
                        (deps, s) -> {
                            throw new RuntimeException("charge failed");
                        },
                        noRetry);
                d.step("fulfill", String.class, (deps, s) -> "fulfilled").dependsOn(charge);
                d.step("refund", String.class, (deps, s) -> "refunded")
                        .dependsOn(charge)
                        .triggerRule(TriggerRule.ALL_FAILED);
                d.step("audit", String.class, (deps, s) -> "audited")
                        .dependsOn(charge)
                        .triggerRule(TriggerRule.ALL_DONE);
            });
            ref.set(r);
            return "ok";
        });
        assertSucceeded(runner);
        DagResult r = ref.get();
        assertEquals(TaskStatus.FAILED, r.getStatus("charge").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("fulfill").orElseThrow());
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("refund").orElseThrow());
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("audit").orElseThrow());
        assertEquals(DagCompletionReason.COMPLETED_WITH_FAILURES, r.completionReason());
        assertCounts(r, 2, 1, 1, 4);
        RECORDS.put("DAG-2", record("DAG-2", r, List.of("charge", "fulfill", "refund", "audit")));
    }

    // ── DAG-3 ─────────────────────────────────────────────────────────────────
    @Test
    void dag3_compensationChargeSucceeds() {
        var ref = new AtomicReference<DagResult>();
        var runner = LocalDurableTestRunner.create(String.class, (in, ctx) -> {
            DagResult r = ctx.dag("dag3", d -> {
                var charge = d.step("charge", String.class, (deps, s) -> "charged");
                d.step("fulfill", String.class, (deps, s) -> "fulfilled").dependsOn(charge);
                d.step("refund", String.class, (deps, s) -> "refunded")
                        .dependsOn(charge)
                        .triggerRule(TriggerRule.ALL_FAILED);
                d.step("audit", String.class, (deps, s) -> "audited")
                        .dependsOn(charge)
                        .triggerRule(TriggerRule.ALL_DONE);
            });
            ref.set(r);
            return "ok";
        });
        assertSucceeded(runner);
        DagResult r = ref.get();
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("charge").orElseThrow());
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("fulfill").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("refund").orElseThrow());
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("audit").orElseThrow());
        assertEquals(DagCompletionReason.ALL_COMPLETED, r.completionReason());
        assertCounts(r, 3, 0, 1, 4);
        RECORDS.put("DAG-3", record("DAG-3", r, List.of("charge", "fulfill", "refund", "audit")));
    }

    // ── DAG-4 ─────────────────────────────────────────────────────────────────
    @Test
    void dag4_runIfBranching() {
        var ref = new AtomicReference<DagResult>();
        var runner = LocalDurableTestRunner.create(String.class, (in, ctx) -> {
            DagResult r = ctx.dag("dag4", d -> {
                var classify = d.step("classify", String.class, (deps, s) -> "review");
                d.step("publish", String.class, (deps, s) -> "published")
                        .reads(classify)
                        .runIf(deps -> "publish".equals(deps.get(classify)));
                d.step("review", String.class, (deps, s) -> "reviewed")
                        .reads(classify)
                        .runIf(deps -> "review".equals(deps.get(classify)));
                d.step("block", String.class, (deps, s) -> "blocked")
                        .reads(classify)
                        .runIf(deps -> "block".equals(deps.get(classify)));
            });
            ref.set(r);
            return "ok";
        });
        assertSucceeded(runner);
        DagResult r = ref.get();
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("review").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("publish").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("block").orElseThrow());
        assertEquals(DagCompletionReason.ALL_COMPLETED, r.completionReason());
        assertCounts(r, 2, 0, 2, 4);
        RECORDS.put("DAG-4", record("DAG-4", r, List.of("classify", "publish", "review", "block")));
    }

    // ── DAG-5 ─────────────────────────────────────────────────────────────────
    @Test
    void dag5_triggerMatrixEmptyUpstream() {
        var ref = new AtomicReference<DagResult>();
        var runner = LocalDurableTestRunner.create(String.class, (in, ctx) -> {
            DagResult r = ctx.dag("dag5", d -> {
                d.step("r_all_success", String.class, (deps, s) -> "ok").triggerRule(TriggerRule.ALL_SUCCESS);
                d.step("r_all_failed", String.class, (deps, s) -> "ok").triggerRule(TriggerRule.ALL_FAILED);
                d.step("r_all_done", String.class, (deps, s) -> "ok").triggerRule(TriggerRule.ALL_DONE);
                d.step("r_one_success", String.class, (deps, s) -> "ok").triggerRule(TriggerRule.ONE_SUCCESS);
                d.step("r_one_failed", String.class, (deps, s) -> "ok").triggerRule(TriggerRule.ONE_FAILED);
                d.step("r_none_failed", String.class, (deps, s) -> "ok").triggerRule(TriggerRule.NONE_FAILED);
            });
            ref.set(r);
            return "ok";
        });
        assertSucceeded(runner);
        DagResult r = ref.get();
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("r_all_success").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("r_all_failed").orElseThrow());
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("r_all_done").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("r_one_success").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("r_one_failed").orElseThrow());
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("r_none_failed").orElseThrow());
        assertEquals(DagCompletionReason.ALL_COMPLETED, r.completionReason());
        assertCounts(r, 3, 0, 3, 6);
        RECORDS.put(
                "DAG-5",
                record(
                        "DAG-5",
                        r,
                        List.of(
                                "r_all_success",
                                "r_all_failed",
                                "r_all_done",
                                "r_one_success",
                                "r_one_failed",
                                "r_none_failed")));
    }

    // ── DAG-6 ─────────────────────────────────────────────────────────────────
    @Test
    void dag6_triggerMatrixMixed() {
        var noRetry = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .build();
        var ref = new AtomicReference<DagResult>();
        var runner = LocalDurableTestRunner.create(String.class, (in, ctx) -> {
            DagResult r = ctx.dag("dag6", d -> {
                var upOk = d.step("up_ok", String.class, (deps, s) -> "ok");
                var upFail = d.step(
                        "up_fail",
                        String.class,
                        (deps, s) -> {
                            throw new RuntimeException("boom");
                        },
                        noRetry);
                d.step("c_all_success", String.class, (deps, s) -> "c")
                        .dependsOn(upOk, upFail)
                        .triggerRule(TriggerRule.ALL_SUCCESS);
                d.step("c_all_failed", String.class, (deps, s) -> "c")
                        .dependsOn(upOk, upFail)
                        .triggerRule(TriggerRule.ALL_FAILED);
                d.step("c_all_done", String.class, (deps, s) -> "c")
                        .dependsOn(upOk, upFail)
                        .triggerRule(TriggerRule.ALL_DONE);
                d.step("c_one_success", String.class, (deps, s) -> "c")
                        .dependsOn(upOk, upFail)
                        .triggerRule(TriggerRule.ONE_SUCCESS);
                d.step("c_one_failed", String.class, (deps, s) -> "c")
                        .dependsOn(upOk, upFail)
                        .triggerRule(TriggerRule.ONE_FAILED);
                d.step("c_none_failed", String.class, (deps, s) -> "c")
                        .dependsOn(upOk, upFail)
                        .triggerRule(TriggerRule.NONE_FAILED);
            });
            ref.set(r);
            return "ok";
        });
        assertSucceeded(runner);
        DagResult r = ref.get();
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("up_ok").orElseThrow());
        assertEquals(TaskStatus.FAILED, r.getStatus("up_fail").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("c_all_success").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("c_all_failed").orElseThrow());
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("c_all_done").orElseThrow());
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("c_one_success").orElseThrow());
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("c_one_failed").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("c_none_failed").orElseThrow());
        assertEquals(DagCompletionReason.COMPLETED_WITH_FAILURES, r.completionReason());
        assertCounts(r, 4, 1, 3, 8);
        RECORDS.put(
                "DAG-6",
                record(
                        "DAG-6",
                        r,
                        List.of(
                                "up_ok",
                                "up_fail",
                                "c_all_success",
                                "c_all_failed",
                                "c_all_done",
                                "c_one_success",
                                "c_one_failed",
                                "c_none_failed")));
    }

    // ── DAG-7 ─────────────────────────────────────────────────────────────────
    @Test
    void dag7_triggerMatrixAllFailed() {
        var noRetry = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .build();
        var ref = new AtomicReference<DagResult>();
        var runner = LocalDurableTestRunner.create(String.class, (in, ctx) -> {
            DagResult r = ctx.dag("dag7", d -> {
                var u1 = d.step(
                        "u1",
                        String.class,
                        (deps, s) -> {
                            throw new RuntimeException("boom");
                        },
                        noRetry);
                var u2 = d.step(
                        "u2",
                        String.class,
                        (deps, s) -> {
                            throw new RuntimeException("boom");
                        },
                        noRetry);
                d.step("k_all_success", String.class, (deps, s) -> "k")
                        .dependsOn(u1, u2)
                        .triggerRule(TriggerRule.ALL_SUCCESS);
                d.step("k_all_failed", String.class, (deps, s) -> "k")
                        .dependsOn(u1, u2)
                        .triggerRule(TriggerRule.ALL_FAILED);
                d.step("k_all_done", String.class, (deps, s) -> "k")
                        .dependsOn(u1, u2)
                        .triggerRule(TriggerRule.ALL_DONE);
                d.step("k_one_success", String.class, (deps, s) -> "k")
                        .dependsOn(u1, u2)
                        .triggerRule(TriggerRule.ONE_SUCCESS);
                d.step("k_one_failed", String.class, (deps, s) -> "k")
                        .dependsOn(u1, u2)
                        .triggerRule(TriggerRule.ONE_FAILED);
                d.step("k_none_failed", String.class, (deps, s) -> "k")
                        .dependsOn(u1, u2)
                        .triggerRule(TriggerRule.NONE_FAILED);
            });
            ref.set(r);
            return "ok";
        });
        assertSucceeded(runner);
        DagResult r = ref.get();
        assertEquals(TaskStatus.FAILED, r.getStatus("u1").orElseThrow());
        assertEquals(TaskStatus.FAILED, r.getStatus("u2").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("k_all_success").orElseThrow());
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("k_all_failed").orElseThrow());
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("k_all_done").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("k_one_success").orElseThrow());
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("k_one_failed").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("k_none_failed").orElseThrow());
        assertEquals(DagCompletionReason.COMPLETED_WITH_FAILURES, r.completionReason());
        assertCounts(r, 3, 2, 3, 8);
        RECORDS.put(
                "DAG-7",
                record(
                        "DAG-7",
                        r,
                        List.of(
                                "u1",
                                "u2",
                                "k_all_success",
                                "k_all_failed",
                                "k_all_done",
                                "k_one_success",
                                "k_one_failed",
                                "k_none_failed")));
    }

    // ── DAG-8 ─────────────────────────────────────────────────────────────────
    @Test
    void dag8_skipCascade() {
        var ref = new AtomicReference<DagResult>();
        var runner = LocalDurableTestRunner.create(String.class, (in, ctx) -> {
            DagResult r = ctx.dag("dag8", d -> {
                var seed = d.step("seed", Integer.class, (deps, s) -> 1);
                var gate = d.step("gate", String.class, (deps, s) -> "gate")
                        .reads(seed)
                        .runIf(deps -> ((Integer) deps.get(seed)) > 100);
                var d1 = d.step("d1", String.class, (deps, s) -> "d1").dependsOn(gate);
                d.step("d2", String.class, (deps, s) -> "d2").dependsOn(d1);
                d.step("sink", String.class, (deps, s) -> "sink")
                        .dependsOn(gate)
                        .triggerRule(TriggerRule.ALL_DONE);
            });
            ref.set(r);
            return "ok";
        });
        assertSucceeded(runner);
        DagResult r = ref.get();
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("seed").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("gate").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("d1").orElseThrow());
        assertEquals(TaskStatus.SKIPPED, r.getStatus("d2").orElseThrow());
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("sink").orElseThrow());
        assertEquals(DagCompletionReason.ALL_COMPLETED, r.completionReason());
        assertCounts(r, 2, 0, 3, 5);
        RECORDS.put("DAG-8", record("DAG-8", r, List.of("seed", "gate", "d1", "d2", "sink")));
    }

    // ── DAG-9 ─────────────────────────────────────────────────────────────────
    @Test
    void dag9_nestedDag() {
        var ref = new AtomicReference<DagResult>();
        var runner = LocalDurableTestRunner.create(String.class, (in, ctx) -> {
            DagResult r = ctx.dag("dag9", d -> {
                var a = d.step("a", Integer.class, (deps, s) -> 2);
                var inner = d.dag("inner", innerCtx -> {
                            var x = innerCtx.step("x", Integer.class, (deps, s) -> 3);
                            innerCtx.step("y", Integer.class, (deps, s) -> deps.get(x) * 10)
                                    .reads(x);
                        })
                        .dependsOn(a);
                d.step("consume", Integer.class, (deps, s) -> {
                            DagResult innerResult = deps.get(inner);
                            return (Integer) innerResult.getResult("y").orElseThrow() + 5;
                        })
                        .reads(inner);
            });
            ref.set(r);
            return "ok";
        });
        assertSucceeded(runner);
        DagResult r = ref.get();
        assertEquals(2, r.getResult("a").orElseThrow());
        assertEquals(35, r.getResult("consume").orElseThrow());
        DagResult inner = (DagResult) r.getResult("inner").orElseThrow();
        assertEquals(DagCompletionReason.ALL_COMPLETED, inner.completionReason());
        assertEquals(3, inner.getResult("x").orElseThrow());
        assertEquals(30, inner.getResult("y").orElseThrow());
        assertCounts(inner, 2, 0, 0, 2);
        // Scope isolation: inner task names are invisible in the outer result.
        assertTrue(r.getStatus("x").isEmpty());
        assertTrue(r.getStatus("y").isEmpty());
        assertEquals(DagCompletionReason.ALL_COMPLETED, r.completionReason());
        assertCounts(r, 3, 0, 0, 3);
        RECORDS.put("DAG-9", record("DAG-9", r, List.of("a", "inner", "consume")));
    }

    // ── DAG-10 ────────────────────────────────────────────────────────────────
    @Test
    void dag10_emptyDag() {
        var ref = new AtomicReference<DagResult>();
        var runner = LocalDurableTestRunner.create(String.class, (in, ctx) -> {
            DagResult r = ctx.dag("dag10", d -> {});
            ref.set(r);
            return "ok";
        });
        assertSucceeded(runner);
        DagResult r = ref.get();
        assertEquals(DagCompletionReason.ALL_COMPLETED, r.completionReason());
        assertCounts(r, 0, 0, 0, 0);
        assertTrue(r.results().isEmpty());
        RECORDS.put("DAG-10", record("DAG-10", r, List.of()));
    }

    // ── DAG-11..15 (validation) ─────────────────────────────────────────────
    // The shipped dag() validates a registered graph via DagValidator inside its child-context body (see
    // DagContextImpl.body: register → DagValidator.validate → schedule). We drive that exact registration+validation
    // path to observe the typed Dag*Exception the SDK raises. NOTE (divergence, verified by
    // validationErrorTypeIsErasedThroughRunner below): end-to-end through the LocalDurableTestRunner the typed
    // exception is ERASED at the runInChildContext failure boundary (DagException carries a null ErrorObject →
    // surfaces as a generic ChildContextFailedException "failed without an error"). The validation SEMANTICS
    // (which graph → which error) match the catalog; only the async propagation loses the type.

    @Test
    void dag11_cycle() {
        assertValidation("DAG-11", DagCyclicDependencyException.class, "DagCyclicDependencyError", () -> {
            var d = new DagContextImpl();
            var p = d.step("p", String.class, (deps, s) -> "p");
            var q = d.step("q", String.class, (deps, s) -> "q");
            p.dependsOn(q);
            q.dependsOn(p);
            return d.tasks();
        });
    }

    @Test
    void dag12_duplicate() {
        assertValidation("DAG-12", DagDuplicateTaskException.class, "DagDuplicateTaskError", () -> {
            var d = new DagContextImpl();
            d.step("dup", String.class, (deps, s) -> "1");
            d.step("dup", String.class, (deps, s) -> "2");
            return d.tasks();
        });
    }

    @Test
    void dag13_invalidNameDash() {
        assertValidation("DAG-13", DagInvalidTaskNameException.class, "DagInvalidTaskNameError", () -> {
            var d = new DagContextImpl();
            d.step("fetch-data", String.class, (deps, s) -> "x");
            return d.tasks();
        });
    }

    @Test
    void dag14_invalidNameReservedToken() {
        assertValidation("DAG-14", DagInvalidTaskNameException.class, "DagInvalidTaskNameError", () -> {
            var d = new DagContextImpl();
            d.step("DAG_NODE_T_root", String.class, (deps, s) -> "x");
            return d.tasks();
        });
    }

    @Test
    void dag15_foreignDependency() {
        assertValidation("DAG-15", DagInvalidDependencyException.class, "DagInvalidDependencyError", () -> {
            // 'foreign' is registered in a DIFFERENT DAG scope, so it is not in this scope's registry.
            var sibling = new DagContextImpl();
            TaskHandle<String> foreign = sibling.step("foreign", String.class, (deps, s) -> "f");
            var d = new DagContextImpl();
            d.step("t", String.class, (deps, s) -> "t").dependsOn(foreign);
            return d.tasks();
        });
    }

    /**
     * Documents the verified divergence: validation errors do NOT surface with their typed identity through the
     * end-to-end runner path — the runInChildContext failure boundary erases the {@link DagException} type (null
     * {@code ErrorObject}) into a generic {@link ChildContextFailedException}.
     */
    @Test
    void validationErrorTypeIsErasedThroughRunner() {
        var caught = new AtomicReference<Throwable>();
        var runner = LocalDurableTestRunner.create(String.class, (in, ctx) -> {
            try {
                ctx.dag("cyc_e2e", d -> {
                    var p = d.step("p", String.class, (deps, s) -> "p");
                    var q = d.step("q", String.class, (deps, s) -> "q");
                    p.dependsOn(q);
                    q.dependsOn(p);
                });
                return "no-error";
            } catch (Throwable t) {
                caught.set(t);
                return "error";
            }
        });
        runner.runUntilComplete("go");
        Throwable t = caught.get();
        assertNotNull(t, "a failure must propagate for an invalid graph");
        assertTrue(
                t instanceof ChildContextFailedException,
                "runner surfaces a generic ChildContextFailedException, got: " + t.getClass());
        assertTrue(
                unwrapDagException(t) == null,
                "DIVERGENCE: the typed Dag*Exception is erased at the child-context boundary");
    }

    // ── DAG-16 ────────────────────────────────────────────────────────────────
    @Test
    void dag16_minSuccessful() {
        var config = DagConfig.builder()
                .maxConcurrency(1)
                .completionConfig(DagCompletionConfig.minSuccessful(3))
                .build();
        var ref = new AtomicReference<DagResult>();
        var runner = LocalDurableTestRunner.create(String.class, (in, ctx) -> {
            DagResult r = ctx.dag(
                    "dag16",
                    d -> {
                        var s1 = d.step("s1", Integer.class, (deps, s) -> 1);
                        var s2 = d.step("s2", Integer.class, (deps, s) -> 2).dependsOn(s1);
                        var s3 = d.step("s3", Integer.class, (deps, s) -> 3).dependsOn(s2);
                        var s4 = d.step("s4", Integer.class, (deps, s) -> 4).dependsOn(s3);
                        d.step("s5", Integer.class, (deps, s) -> 5).dependsOn(s4);
                    },
                    config);
            ref.set(r);
            return "ok";
        });
        assertSucceeded(runner);
        DagResult r = ref.get();
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("s1").orElseThrow());
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("s2").orElseThrow());
        assertEquals(TaskStatus.SUCCEEDED, r.getStatus("s3").orElseThrow());
        // s4, s5 never started → absent from the results map.
        assertTrue(r.getStatus("s4").isEmpty(), "s4 must be absent (never started)");
        assertTrue(r.getStatus("s5").isEmpty(), "s5 must be absent (never started)");
        assertEquals(DagCompletionReason.MIN_SUCCESSFUL_REACHED, r.completionReason());
        assertEquals(3, r.successCount());
        assertEquals(0, r.failureCount());
        assertEquals(0, r.skippedCount());
        // Per spec §2.8: totalCount == number of REGISTERED tasks (5), fixed and independent of early completion.
        // s4, s5 never started and stay absent from the results map (§2.9/§9.6); getStatus disambiguates.
        assertEquals(5, r.totalCount());
        RECORDS.put("DAG-16", record("DAG-16", r, List.of("s1", "s2", "s3", "s4", "s5")));
    }

    // ── DAG-17 ────────────────────────────────────────────────────────────────
    @Test
    void dag17_toleratedFailureCount() {
        var noRetry = StepConfig.builder()
                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                .build();
        var config = DagConfig.builder()
                .maxConcurrency(1)
                .completionConfig(DagCompletionConfig.toleratedFailureCount(1))
                .build();
        var ref = new AtomicReference<DagResult>();
        var runner = LocalDurableTestRunner.create(String.class, (in, ctx) -> {
            DagResult r = ctx.dag(
                    "dag17",
                    d -> {
                        var t1 = d.step(
                                "t1",
                                String.class,
                                (deps, s) -> {
                                    throw new RuntimeException("boom");
                                },
                                noRetry);
                        var t2 = d.step(
                                        "t2",
                                        String.class,
                                        (deps, s) -> {
                                            throw new RuntimeException("boom");
                                        },
                                        noRetry)
                                .dependsOn(t1)
                                .triggerRule(TriggerRule.ALL_DONE);
                        var t3 = d.step(
                                        "t3",
                                        String.class,
                                        (deps, s) -> {
                                            throw new RuntimeException("boom");
                                        },
                                        noRetry)
                                .dependsOn(t2)
                                .triggerRule(TriggerRule.ALL_DONE);
                        d.step(
                                        "t4",
                                        String.class,
                                        (deps, s) -> {
                                            throw new RuntimeException("boom");
                                        },
                                        noRetry)
                                .dependsOn(t3)
                                .triggerRule(TriggerRule.ALL_DONE);
                    },
                    config);
            ref.set(r);
            return "ok";
        });
        assertSucceeded(runner);
        DagResult r = ref.get();
        assertEquals(TaskStatus.FAILED, r.getStatus("t1").orElseThrow());
        assertEquals(TaskStatus.FAILED, r.getStatus("t2").orElseThrow());
        assertTrue(r.getStatus("t3").isEmpty(), "t3 must be absent (never started)");
        assertTrue(r.getStatus("t4").isEmpty(), "t4 must be absent (never started)");
        assertEquals(DagCompletionReason.FAILURE_TOLERANCE_EXCEEDED, r.completionReason());
        assertEquals(0, r.successCount());
        assertEquals(2, r.failureCount());
        assertEquals(0, r.skippedCount());
        // Per spec §2.8: totalCount == number of REGISTERED tasks (4), fixed and independent of early completion.
        // t3, t4 never started and stay absent from the results map (§2.9/§9.6); getStatus disambiguates.
        assertEquals(4, r.totalCount());
        RECORDS.put("DAG-17", record("DAG-17", r, List.of("t1", "t2", "t3", "t4")));
    }

    // ── DAG-18 ────────────────────────────────────────────────────────────────
    // NOT-APPLICABLE: custom result-based completion is [TS + Go ONLY]. Java has no completion-predicate hook in v1
    // (threshold-only, see DagCompletionConfig). No record emitted for DAG-18 (java.json carries 18 records).

    // ── DAG-19 ────────────────────────────────────────────────────────────────
    @Test
    void dag19_orderIndependence() {
        // run1: register b before c; run2: register c before b (perturbed completion/registration order).
        Object rec1 = runDiamond(true);
        Object rec2 = runDiamond(false);
        // The two emitted records MUST be byte-identical regardless of branch order (name-based IDs).
        assertEquals(toJson(rec1, ""), toJson(rec2, ""), "DAG-19 record must be order-independent");
        RECORDS.put("DAG-19", rec1);
    }

    private Object runDiamond(boolean bFirst) {
        var ref = new AtomicReference<DagResult>();
        var runner = LocalDurableTestRunner.create(String.class, (in, ctx) -> {
            DagResult r = ctx.dag("dag19", d -> {
                var root = d.step("root", Integer.class, (deps, s) -> 100);
                TaskHandle<Integer> b;
                TaskHandle<Integer> c;
                if (bFirst) {
                    b = d.step("b", Integer.class, (deps, s) -> deps.get(root) + 1)
                            .reads(root);
                    c = d.step("c", Integer.class, (deps, s) -> deps.get(root) + 2)
                            .reads(root);
                } else {
                    c = d.step("c", Integer.class, (deps, s) -> deps.get(root) + 2)
                            .reads(root);
                    b = d.step("b", Integer.class, (deps, s) -> deps.get(root) + 1)
                            .reads(root);
                }
                d.step("merge", Integer.class, (deps, s) -> deps.get(b) + deps.get(c))
                        .reads(b, c);
            });
            ref.set(r);
            return "ok";
        });
        assertSucceeded(runner);
        DagResult r = ref.get();
        assertEquals(100, r.getResult("root").orElseThrow());
        assertEquals(101, r.getResult("b").orElseThrow());
        assertEquals(102, r.getResult("c").orElseThrow());
        assertEquals(203, r.getResult("merge").orElseThrow());
        assertEquals(DagCompletionReason.ALL_COMPLETED, r.completionReason());
        assertCounts(r, 4, 0, 0, 4);
        return record("DAG-19", r, List.of("root", "b", "c", "merge"));
    }

    // ── emission ──────────────────────────────────────────────────────────────
    @AfterAll
    static void writeConformanceJson() throws IOException {
        // 18 applicable scenarios for Java (DAG-18 is TS+Go only).
        assertEquals(18, RECORDS.size(), "Java must emit exactly 18 conformance records");
        assertTrue(RECORDS.containsKey("DAG-1") && RECORDS.containsKey("DAG-19"));
        assertTrue(!RECORDS.containsKey("DAG-18"), "DAG-18 is [TS+Go only] and must not be emitted");
        String json = toJson(RECORDS, "") + "\n";
        Path out = Path.of(OUT);
        Files.createDirectories(out.getParent());
        Files.writeString(out, json, StandardCharsets.UTF_8);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static void assertSucceeded(LocalDurableTestRunner<String, String> runner) {
        var result = runner.runUntilComplete("go");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    private static void assertCounts(DagResult r, int success, int failure, int skipped, int total) {
        assertEquals(success, r.successCount(), "successCount");
        assertEquals(failure, r.failureCount(), "failureCount");
        assertEquals(skipped, r.skippedCount(), "skippedCount");
        assertEquals(total, r.totalCount(), "totalCount");
    }

    /**
     * Asserts the shipped {@link DagValidator} (the exact validation the {@code dag()} body runs) raises the expected
     * typed {@link DagException} for a graph, and records the normalized error token.
     */
    private void assertValidation(
            String scenario,
            Class<? extends DagException> expected,
            String normalizedToken,
            Supplier<List<TaskHandleImpl<?>>> graph) {
        List<TaskHandleImpl<?>> tasks = graph.get();
        DagException dagEx = assertThrows(DagException.class, () -> DagValidator.validate(tasks), scenario);
        assertTrue(
                expected.isInstance(dagEx),
                scenario + ": expected " + expected.getSimpleName() + " but got "
                        + dagEx.getClass().getSimpleName());
        RECORDS.put(scenario, validationRecord(scenario, normalizedToken));
    }

    private static DagException unwrapDagException(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof DagException de) {
                return de;
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return null;
    }

    /** Builds a normalized conformance record (Part B schema) from a completed DagResult. */
    private static Map<String, Object> record(String scenario, DagResult r, List<String> registeredNames) {
        Map<String, Object> rec = new TreeMap<>();
        rec.put("scenario", scenario);

        Map<String, Object> tasks = new TreeMap<>();
        for (Map.Entry<String, TaskExecution<?>> e : r.results().entrySet()) {
            TaskExecution<?> exec = e.getValue();
            tasks.put(e.getKey(), taskObject(exec));
        }
        rec.put("tasks", tasks);

        rec.put("completion_reason", r.completionReason().name());

        Map<String, Object> counts = new TreeMap<>();
        counts.put("success", r.successCount());
        counts.put("failure", r.failureCount());
        counts.put("skipped", r.skippedCount());
        counts.put("total", r.totalCount());
        rec.put("counts", counts);

        rec.put("structural_id_checks", structuralIdChecks(registeredNames));
        rec.put("validation_error", null);
        return rec;
    }

    private static Map<String, Object> taskObject(TaskExecution<?> exec) {
        Map<String, Object> t = new TreeMap<>();
        TaskStatus status = exec.status();
        t.put("status", status.name());
        t.put(
                "result",
                status == TaskStatus.SUCCEEDED ? normalizeResult(exec.result().orElse(null)) : null);
        t.put("error_type", status == TaskStatus.FAILED ? STEP_ERROR : null);
        t.put(
                "skip_reason",
                status == TaskStatus.SKIPPED ? exec.skipReason().map(Enum::name).orElse(null) : null);
        return t;
    }

    /** Normalizes a task result: nested DagResult → {completion_reason, counts}; primitives/strings pass through. */
    private static Object normalizeResult(Object value) {
        if (value instanceof DagResult nested) {
            Map<String, Object> norm = new TreeMap<>();
            norm.put("completion_reason", nested.completionReason().name());
            Map<String, Object> counts = new TreeMap<>();
            counts.put("success", nested.successCount());
            counts.put("failure", nested.failureCount());
            counts.put("skipped", nested.skippedCount());
            counts.put("total", nested.totalCount());
            norm.put("counts", counts);
            return norm;
        }
        return value;
    }

    /**
     * Computes and verifies the four per-language structural entity-ID invariants against Java's own ID scheme
     * ({@code hash(contextId + "-DAG_NODE_T_" + taskName)}, see {@link OperationIdGenerator} / {@link DagExecutor}).
     * Raw hashes are NOT compared cross-language; these are Java-local structural checks. Empty DAG → all four
     * vacuously true.
     */
    private static Map<String, Object> structuralIdChecks(List<String> names) {
        String ctxId = "conformance-ctx";
        var gen = new OperationIdGenerator(ctxId);
        // Precompute a pool of counter-based sibling IDs for the disjointness check.
        Set<String> counterIds = new HashSet<>();
        for (int i = 0; i < names.size() + 8; i++) {
            counterIds.add(gen.nextOperationId());
        }
        boolean nameBased = true;
        boolean hasDelimiter = true;
        boolean dashFree = true;
        boolean disjointFromCounter = true;
        for (String name : names) {
            String preImage = ctxId + "-" + DagExecutor.NODE_PREFIX + name;
            String id = gen.operationIdForName(DagExecutor.NODE_PREFIX + name);
            // name_based: the id is derived from the task name (recomputable from the name pre-image), not a counter.
            if (!id.equals(OperationIdGenerator.hashOperationId(preImage))) {
                nameBased = false;
            }
            // has_delimiter: the pre-image contains DAG_NODE_T_ exactly once (per nesting level).
            if (countOccurrences(preImage, DagExecutor.NODE_PREFIX) != 1) {
                hasDelimiter = false;
            }
            // dash_free: the name matches the DAG charset (no dash, no reserved token).
            if (!name.matches("^[a-zA-Z0-9_]+$") || name.contains(DagExecutor.NODE_PREFIX)) {
                dashFree = false;
            }
            // disjoint_from_counter: a name-based id never collides with a sibling counter id.
            if (counterIds.contains(id)) {
                disjointFromCounter = false;
            }
        }
        // For non-validation scenarios these MUST hold (vacuously true for the empty DAG).
        assertTrue(nameBased, "structural: name_based");
        assertTrue(hasDelimiter, "structural: has_delimiter");
        assertTrue(dashFree, "structural: dash_free");
        assertTrue(disjointFromCounter, "structural: disjoint_from_counter");

        Map<String, Object> checks = new TreeMap<>();
        checks.put("name_based", nameBased);
        checks.put("has_delimiter", hasDelimiter);
        checks.put("dash_free", dashFree);
        checks.put("disjoint_from_counter", disjointFromCounter);
        return checks;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static Map<String, Object> validationRecord(String scenario, String token) {
        Map<String, Object> rec = new TreeMap<>();
        rec.put("scenario", scenario);
        rec.put("tasks", new TreeMap<String, Object>());
        rec.put("completion_reason", null);
        Map<String, Object> counts = new TreeMap<>();
        counts.put("success", 0);
        counts.put("failure", 0);
        counts.put("skipped", 0);
        counts.put("total", 0);
        rec.put("counts", counts);
        Map<String, Object> checks = new TreeMap<>();
        checks.put("name_based", false);
        checks.put("has_delimiter", false);
        checks.put("dash_free", false);
        checks.put("disjoint_from_counter", false);
        rec.put("structural_id_checks", checks);
        rec.put("validation_error", token);
        return rec;
    }

    // ── deterministic JSON writer (UTF-8, 2-space indent, sorted keys, ints without decimals) ──
    @SuppressWarnings("unchecked")
    private static String toJson(Object v, String indent) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String s) {
            return quote(s);
        }
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            return v.toString();
        }
        if (v instanceof Map<?, ?> raw) {
            if (raw.isEmpty()) {
                return "{}";
            }
            Map<String, Object> m = new TreeMap<>();
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                m.put((String) e.getKey(), e.getValue());
            }
            StringBuilder sb = new StringBuilder("{\n");
            String ni = indent + "  ";
            int i = 0;
            int n = m.size();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                sb.append(ni).append(quote(e.getKey())).append(": ").append(toJson(e.getValue(), ni));
                if (++i < n) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append(indent).append("}");
            return sb.toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value type: " + v.getClass());
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append("\"").toString();
    }
}
