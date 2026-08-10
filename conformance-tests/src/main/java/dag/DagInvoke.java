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

/**
 * 10-10: DAG task that is an invoke of another Lambda (flat invoke op under the DAG).
 *
 * <p>prep(step-&gt;21) -&gt; call(invoke[prep]=echo(21)=21) -&gt; done(step[call]=call*2=42). The invoke target is the
 * echo function named by the {@code TARGET_FUNCTION_NAME} env var, so {@code call} resolves to the payload it was sent
 * ({@code prep}=21). maxConcurrency=1 for a deterministic topological order. Every task succeeds → ALL_COMPLETED. This
 * scenario suspends and resumes (the invoke completes in a later invocation). Returns the canonical summary from
 * 10-10.yaml.
 */
public class DagInvoke extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        String functionName = System.getenv("TARGET_FUNCTION_NAME");
        DagResult r = dag(
                "invokedag",
                d -> {
                    var prep = d.step("prep", Integer.class, (deps, s) -> 21);
                    var call = d.invoke("call", functionName, Integer.class, deps -> deps.get(prep)
                                    .orElseThrow())
                            .reads(prep);
                    d.step("done", Integer.class, (deps, s) -> deps.get(call).orElseThrow() * 2)
                            .reads(call);
                },
                DagConfig.builder().maxConcurrency(1).build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("statuses", DagSummary.statuses(r));
        out.put("counts", DagSummary.counts(r));
        out.put("call", r.getResult("call").orElseThrow());
        out.put("done", r.getResult("done").orElseThrow());
        return out;
    }
}
