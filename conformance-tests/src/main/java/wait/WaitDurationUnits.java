// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 2-4: Wait with different duration units (minutes) */
public class WaitDurationUnits extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        context.wait(null, Duration.ofMinutes(1));
        return "Wait with minutes completed";
    }
}
