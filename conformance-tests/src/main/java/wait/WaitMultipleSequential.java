// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait;

import java.time.Duration;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 2-3: Multiple sequential waits */
public class WaitMultipleSequential extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        context.wait("wait-1", Duration.ofSeconds(2));
        context.wait("wait-2", Duration.ofSeconds(2));
        return Map.of("completedWaits", 2);
    }
}
