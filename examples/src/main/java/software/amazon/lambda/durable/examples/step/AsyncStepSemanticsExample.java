// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.step;

import java.time.Duration;
import java.util.UUID;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.StepSemantics;
import software.amazon.lambda.durable.examples.types.GreetingRequest;

/**
 * Example of {@link StepSemantics#AT_LEAST_ONCE_ASYNC} as the default step semantics.
 *
 * <p>Every step returns its result without waiting for its SUCCEED checkpoint, except the step that creates an
 * idempotency key. On replay, an async step can return a different value. So the key step sets
 * {@link StepSemantics#AT_LEAST_ONCE}, and a later step always sends the same key.
 *
 * <p>The wait ends the first invocation. The second invocation replays the steps before the wait.
 */
public class AsyncStepSemanticsExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder()
                .withDefaultStepSemantics(StepSemantics.AT_LEAST_ONCE_ASYNC)
                .build();
    }

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        // The key must be the same on replay, so this step waits for its SUCCEED checkpoint.
        var idempotencyKey = context.step(
                "create-idempotency-key",
                String.class,
                stepCtx -> UUID.randomUUID().toString(),
                StepConfig.builder()
                        .semanticsPerRetry(StepSemantics.AT_LEAST_ONCE)
                        .build());

        // Uses the default semantics. A real call would pass the key to the service.
        var greeting = context.step(
                "send-greeting", String.class, stepCtx -> "Hello, " + input.getName() + " (" + idempotencyKey + ")");

        context.wait("pause", Duration.ofSeconds(1));

        return context.step("finish", String.class, stepCtx -> greeting + "!");
    }
}
