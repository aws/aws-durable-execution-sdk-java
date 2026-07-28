// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.time.Duration;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.RetryDecision;

/**
 * 10-3: Plugin attempt hooks fire per step attempt with attempt number and outcome.
 *
 * <p>A single step that fails on the first attempt and succeeds on the second (driven by the SDK's built-in
 * {@code getAttempt()} and a real retry strategy), configured with {@link ConformanceLoggingPlugin}. The plugin logs
 * {@code attempt-start n=<n>} / {@code attempt-end n=<n> outcome=<SUCCEEDED|FAILED>} from the user-function hooks,
 * which run on the same thread as the step body so their order is deterministic.
 */
@SuppressWarnings("deprecation")
public class PluginAttemptHooksRetry extends DurableHandler<Object, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder()
                .withPlugins(new ConformanceLoggingPlugin("CONFPLUGIN"))
                .build();
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step(
                "retry-step",
                String.class,
                stepCtx -> {
                    // Fail on the first attempt, succeed on the second, using the SDK's
                    // built-in 1-based attempt number.
                    if (stepCtx.getAttempt() < 2) {
                        throw new RuntimeException("Attempt " + stepCtx.getAttempt() + " failed");
                    }
                    return "Operation succeeded";
                },
                StepConfig.builder()
                        .retryStrategy((error, attempt) -> {
                            if (attempt >= 3) return RetryDecision.fail();
                            return RetryDecision.retry(Duration.ofSeconds(1));
                        })
                        .build());
    }
}
