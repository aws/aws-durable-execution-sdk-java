// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 3-1: Child context basic (single step inside) */
public class ChildBasic extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.runInChildContext(
                "child-basic", String.class, child -> child.step("inner-step", String.class, stepCtx -> input));
    }
}
