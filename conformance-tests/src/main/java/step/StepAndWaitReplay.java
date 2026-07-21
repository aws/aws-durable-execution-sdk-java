// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 1-8: Step and wait with replay */
public class StepAndWaitReplay extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        String result = context.step("compute", String.class, stepCtx -> "computed");
        context.wait(null, Duration.ofSeconds(2));
        return result;
    }
}
