// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.invoke;

import java.time.Duration;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.operation.DurableInvokeOperation;
import software.amazon.lambda.durable.operation.DurableWithRetryOperation;
import software.amazon.lambda.durable.operation.DurableWithRetryOperation.WithRetryConfig;
import software.amazon.lambda.durable.operation.DurableWithRetryOperation.WithRetryContext;
import software.amazon.lambda.durable.retry.RetryDecision;

/**
 * Example demonstrating {@link DurableWithRetryOperation} with {@link DurableInvokeOperation}.
 *
 * <p>Retries a chained Lambda invocation up to 3 times with a fixed 2-second backoff between attempts. Each attempt
 * uses a unique operation name ({@code "call-greeting-1"}, {@code "call-greeting-2"}, etc.) so the execution history
 * stays clean and replay-safe.
 *
 * <p>A {@code null} name is used, so attempts are grouped under a default-named child context.
 */
public class RetryInvokeExample extends DurableHandler<GreetingRequest, String> {

    private static final int MAX_ATTEMPTS = 3;

    @Override
    public String handleRequest(GreetingRequest input) {
        var targetFunctionName =
                System.getenv().getOrDefault("FUNCTION_NAME_PREFIX", "") + "simple-step-example:$LATEST";

        return DurableWithRetryOperation.withRetry(
                null,
                () -> {
                    var attempt = WithRetryContext.getCurrentContext().getAttempt();
                    return DurableInvokeOperation.invoke(
                            "call-greeting-" + attempt, targetFunctionName, input, String.class);
                },
                WithRetryConfig.builder()
                        .retryStrategy((error, attempt) -> attempt < MAX_ATTEMPTS
                                ? RetryDecision.retry(Duration.ofSeconds(2))
                                : RetryDecision.fail())
                        .build());
    }
}
