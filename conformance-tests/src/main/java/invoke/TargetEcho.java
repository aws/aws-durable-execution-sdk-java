// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** Target function that echoes back whatever it receives (with a short wait to ensure caller suspends). */
public class TargetEcho extends DurableHandler<Object, Object> {

    @Override
    public Object handleRequest(Object input, DurableContext context) {
        context.wait(null, Duration.ofSeconds(1));
        return input;
    }
}
