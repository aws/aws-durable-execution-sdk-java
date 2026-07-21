// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** Target function that takes longer than the invoke timeout. */
public class TargetSlow extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        try {
            Thread.sleep(60000); // Sleep 60 seconds to exceed timeout
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "should-not-reach";
    }
}
