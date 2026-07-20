// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 2-5: Wait with long duration (1 hour) */
public class WaitLongDuration extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        context.wait(null, Duration.ofHours(1));
        return "Wait with hours completed";
    }
}
