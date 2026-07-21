// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 2-1: Wait basic */
public class WaitBasic extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        context.wait(null, Duration.ofSeconds(2));
        return "Wait completed";
    }
}
