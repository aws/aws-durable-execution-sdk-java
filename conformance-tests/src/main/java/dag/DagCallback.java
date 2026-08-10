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
 * 10-11: DAG task that is a callback / wait-for-callback (flat callback container under the DAG).
 *
 * <p>pre(step-&gt;"ready") -&gt; cb(callback[pre]) -&gt; post(step[cb]=cb+"_done"). The submitter receives the
 * generated callback id and does nothing durable (same as the 7-1 wait-for-callback handler); the conformance runner
 * completes the callback externally with a success payload. maxConcurrency=1 for a deterministic topological order.
 * Every task succeeds → ALL_COMPLETED. This scenario suspends until the external callback arrives. Returns the
 * canonical summary from 10-11.yaml.
 */
public class DagCallback extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        DagResult r = dag(
                "callbackdag",
                d -> {
                    var pre = d.step("pre", String.class, (deps, s) -> "ready");
                    var cb = d.callback("cb", String.class, (deps, callbackId, stepCtx) -> {
                                // Submitter receives the callback id; nothing durable to do.
                            })
                            .reads(pre);
                    d.step(
                                    "post",
                                    String.class,
                                    (deps, s) -> stripQuotes(deps.get(cb).orElseThrow()) + "_done")
                            .reads(cb);
                },
                DagConfig.builder().maxConcurrency(1).build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", r.completionReason().name());
        out.put("statuses", DagSummary.statuses(r));
        out.put("counts", DagSummary.counts(r));
        out.put("cb", stripQuotes((String) r.getResult("cb").orElseThrow()));
        out.put("post", r.getResult("post").orElseThrow());
        return out;
    }

    /**
     * Strips a single pair of surrounding double-quote characters from the callback result if present. The default
     * callback deserializer is documented as returning the raw payload text (quotes included); the runner's payload is
     * alphanumeric so this normalization is unambiguous.
     */
    private static String stripQuotes(String value) {
        if (value != null && value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
