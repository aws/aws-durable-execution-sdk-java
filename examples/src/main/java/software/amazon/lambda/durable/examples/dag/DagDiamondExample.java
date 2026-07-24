// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.dag;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.dag.DagResult;

/**
 * DAG example: a diamond (a -&gt; {b, c} -&gt; dd) exercising typed inline dependencies via {@code .reads(...)} and
 * {@code deps.get(...)}. Returns the terminal join result so the cloud test can assert on a simple string.
 */
public class DagDiamondExample extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        DagResult r = context.dag("etl", d -> {
            var a = d.step("a", String.class, (deps, s) -> "A");
            var b = d.step("b", String.class, (deps, s) -> deps.get(a) + "B").reads(a);
            var c = d.step("c", String.class, (deps, s) -> deps.get(a) + "C").reads(a);
            d.step("dd", String.class, (deps, s) -> deps.get(b) + deps.get(c)).reads(b, c);
        });
        return (String) r.getResult("dd").orElse("MISSING");
    }
}
