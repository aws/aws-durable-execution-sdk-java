// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package general;

import java.time.Duration;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.logging.LoggerConfig;

/** 11-1: Replay rejects an operation-type mismatch. */
public class ReplayOperationIdentity extends DurableHandler<Object, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder()
                .withLoggerConfig(LoggerConfig.withReplayLogging())
                .build();
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        if (context.isReplaying()) {
            context.getLogger().info("DETERMINISM_REPLAY_CANARY");
            return context.step("identity-slot", String.class, stepContext -> {
                stepContext.getLogger().info("DETERMINISM_STEP_BODY_EXECUTED");
                return "unexpected";
            });
        }

        context.wait("identity-slot", Duration.ofSeconds(1));
        return null;
    }
}
