// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package callback;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.CallbackConfig;

/** 4-5: Callback with heartbeat then success. */
public class CallbackHeartbeatSuccess extends DurableHandler<String, String> {
    @Override
    public String handleRequest(String input, DurableContext context) {
        var config = CallbackConfig.builder()
                .heartbeatTimeout(Duration.ofSeconds(10))
                .build();
        var callback = context.createCallback(input, String.class, config);
        return callback.get();
    }
}
