// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 2-2: Wait with name */
public class WaitWithName extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        context.wait("custom_wait_name", Duration.ofSeconds(2));
        return "Wait with name completed";
    }
}
