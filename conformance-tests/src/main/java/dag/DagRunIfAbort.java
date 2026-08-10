// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import static software.amazon.lambda.durable.dag.DagOperations.dag;

import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.dag.TriggerRule;

/**
 * 10-12: DAG {@code runIf} abort path on the wire.
 *
 * <p>gate(step-&gt;1) -&gt; guarded(step[gate], runIf throws "predicate boom") -&gt; refund(step .after(guarded),
 * ALL_FAILED). A throwing {@code runIf} is a defect in deterministic code, so the scheduler ABORTS: guarded gets no
 * terminal state and is never invoked, refund (the ALL_FAILED compensation) never runs, and {@code dag(...)} fails with
 * a typed {@code DagPredicateException}. The exception propagates out of this handler, so the execution FAILS — the DAG
 * container checkpoints ContextFailed SubType=Dag after gate succeeded. maxConcurrency=1 keeps a deterministic order so
 * this scenario retains full history assertions. See RUNIF_ABORT_CONTRACT / 10-12.yaml.
 */
public class DagRunIfAbort extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        // dag(...) throws DagPredicateException when guarded's runIf throws; we deliberately do NOT catch it, so the
        // execution fails. The summary below is unreachable and exists only to mirror the sibling handlers' shape.
        DagResult r = dag(
                "abortdag",
                d -> {
                    var gate = d.step("gate", Integer.class, (deps, s) -> 1);
                    var guarded = d.step("guarded", String.class, (deps, s) -> "ran")
                            .reads(gate)
                            .runIf(deps -> {
                                throw new IllegalStateException("predicate boom");
                            });
                    d.step("refund", String.class, (deps, s) -> "refunded")
                            .after(guarded)
                            .triggerRule(TriggerRule.ALL_FAILED);
                },
                DagConfig.builder().maxConcurrency(1).build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("statuses", DagSummary.statuses(r));
        out.put("counts", DagSummary.counts(r));
        return out;
    }
}
