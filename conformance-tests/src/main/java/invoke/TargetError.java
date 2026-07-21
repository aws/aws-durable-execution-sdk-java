// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** Target function that waits briefly then throws an exception. */
public class TargetError extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        context.wait(null, Duration.ofSeconds(1));
        throw new RuntimeException("Target function error");
    }
}
