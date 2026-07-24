// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.dag;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.dag.DagResult;

/**
 * DAG example: {@code runIf} conditional branching with skip cascade. The {@code gate} step yields 0, so {@code maybe}'s
 * run-if predicate is false and it is SKIPPED; {@code after} (default ALL_SUCCESS over a skipped upstream) also SKIPS.
 * Returns a pipe-delimited summary of the two task statuses.
 */
public class DagRunIfExample extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        DagResult r = context.dag("cond", d -> {
            var gate = d.step("gate", Integer.class, (deps, s) -> 0);
            var maybe = d.step("maybe", String.class, (deps, s) -> "ran")
                    .reads(gate)
                    .runIf(deps -> ((Integer) deps.get(gate)) > 0);
            d.step("after", String.class, (deps, s) -> "after").after(maybe);
        });
        return r.getStatus("maybe").map(Enum::name).orElse("?")
                + "|"
                + r.getStatus("after").map(Enum::name).orElse("?");
    }
}
