// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 3-15: Child context error without step (error thrown directly in child body) */
public class ChildErrorNoStep extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.runInChildContext("direct-error", String.class, child -> {
            throw new RuntimeException("direct error");
        });
    }
}
