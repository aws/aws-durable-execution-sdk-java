// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.dag;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.dag.DagResult;

/**
 * DAG example: a diamond with an in-DAG {@code wait} node between the concurrent fan-out (b, c) and the join. The wait
 * forces a real suspend/replay on the cloud backend; name-based task IDs must make the join deterministic and the
 * per-task checkpoints must fast-path on resume (no body re-execution). Returns the join result ("ABAC").
 */
public class DagWaitResumeExample extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        DagResult r = context.dag("diamond_wait", d -> {
            var a = d.step("a", String.class, (deps, s) -> "A");
            var b = d.step("b", String.class, (deps, s) -> deps.get(a) + "B").reads(a);
            var c = d.step("c", String.class, (deps, s) -> deps.get(a) + "C").reads(a);
            var w = d.wait("w", Duration.ofSeconds(5)).after(b, c);
            d.step("join", String.class, (deps, s) -> deps.get(b) + deps.get(c))
                    .reads(b, c)
                    .after(w);
        });
        return (String) r.getResult("join").orElse("MISSING");
    }
}
