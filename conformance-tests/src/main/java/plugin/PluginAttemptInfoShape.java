// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.time.Duration;
import java.util.Locale;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.UserFunctionEndInfo;
import software.amazon.lambda.durable.plugin.UserFunctionStartInfo;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * 10-21: Attempt hook info field shape.
 *
 * <p>A single step named {@code "flaky"} that throws on its first attempt and succeeds on the second, driven by the
 * SDK's built-in {@code getAttempt()} and a real exponential-backoff retry strategy (max attempts 3, ~1s delay),
 * returning {@code "ok"}. INTERFACE-SHAPE probe of the per-attempt (user-function) hooks, filtered to step-type
 * operations. Every logged field is read from the CURRENT hook's own info parameter only. Java's
 * {@link UserFunctionStartInfo} carries identity, {@code startTimestamp}, and the 1-based {@code attempt};
 * {@link UserFunctionEndInfo} carries the {@code succeeded} boolean (presented as the {@code outcome} token — a
 * presentation of the API's own data, not a reconstruction) and {@code error}. Replay indicators are emitted for
 * observability but not asserted.
 */
@SuppressWarnings("deprecation")
public class PluginAttemptInfoShape extends DurableHandler<Object, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new AttemptShapePlugin()).build();
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step(
                "flaky",
                String.class,
                stepCtx -> {
                    // Fail on the first attempt, succeed on the second, using the SDK's built-in 1-based attempt
                    // number.
                    if (stepCtx.getAttempt() < 2) {
                        throw new RuntimeException("Attempt " + stepCtx.getAttempt() + " failed");
                    }
                    return "ok";
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.exponentialBackoff(
                                3, Duration.ofSeconds(1), Duration.ofSeconds(10), 1.0, JitterStrategy.NONE))
                        .build());
    }

    private static final class AttemptShapePlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
        }

        @Override
        public void onUserFunctionStart(UserFunctionStartInfo info) {
            if (!PluginSupport.isStep(info.type()) || info.attempt() == null) {
                return;
            }
            boolean hasStartTime = info.startTimestamp() != null;
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"attempt-start\", \"op\": \"%s\", \"name\": \"%s\", "
                            + "\"type\": \"%s\", \"attempt\": %d, \"has_start_time\": %b%s}",
                    info.id(),
                    info.name(),
                    info.type().toUpperCase(Locale.ROOT),
                    info.attempt(),
                    hasStartTime,
                    PluginSupport.arnField(executionArn)));
        }

        @Override
        public void onUserFunctionEnd(UserFunctionEndInfo info) {
            if (!PluginSupport.isStep(info.type()) || info.attempt() == null) {
                return;
            }
            // outcome presents the info's own succeeded boolean; has_error reflects the attempt's error object.
            String outcome = info.succeeded() ? "SUCCEEDED" : "FAILED";
            boolean hasError = info.error() != null;
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"attempt-end\", \"op\": \"%s\", \"name\": \"%s\", "
                            + "\"type\": \"%s\", \"attempt\": %d, \"outcome\": \"%s\", \"has_error\": %b%s}",
                    info.id(),
                    info.name(),
                    info.type().toUpperCase(Locale.ROOT),
                    info.attempt(),
                    outcome,
                    hasError,
                    PluginSupport.arnField(executionArn)));
        }
    }
}
