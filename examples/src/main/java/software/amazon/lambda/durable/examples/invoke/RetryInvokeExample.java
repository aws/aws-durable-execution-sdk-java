// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.invoke;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.RetryOperationConfig;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.util.RetryOperationHelper;

/**
 * Example demonstrating {@link RetryOperationHelper} with {@code context.invoke}.
 *
 * <p>Retries a chained Lambda invocation up to 3 times with a fixed 2-second backoff between attempts. Each attempt
 * uses a unique operation name ({@code "call-greeting-1"}, {@code "call-greeting-2"}, etc.) so the execution history
 * stays clean and replay-safe.
 *
 * <p>The anonymous form is used, so attempts run directly in the caller's context without child-context wrapping.
 */
public class RetryInvokeExample extends DurableHandler<GreetingRequest, String> {

    private static final int MAX_ATTEMPTS = 3;

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        return RetryOperationHelper.retryOperation(
                context,
                (ctx, attempt) -> ctx.invoke(
                        "call-greeting-" + attempt,
                        "simple-step-example" + input.getName() + ":$LATEST",
                        input,
                        String.class),
                RetryOperationConfig.builder()
                        .retryStrategy((error, attempt) -> attempt < MAX_ATTEMPTS
                                ? RetryDecision.retry(Duration.ofSeconds(2))
                                : RetryDecision.fail())
                        .build());
    }
}
