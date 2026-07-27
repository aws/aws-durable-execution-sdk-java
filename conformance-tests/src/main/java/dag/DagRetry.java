// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package dag;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.dag.DagConfig;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * 10-16: retry inside a DAG (a task retries and eventually succeeds).
 *
 * <p>{@code flaky} is a step carrying an explicit per-task retry strategy of 3 attempts with a fixed 1-second delay (the
 * minimum the SDK allows; no exponential backoff worth waiting on). Its body reads the 1-based attempt number from its
 * {@link software.amazon.lambda.durable.StepContext} via {@code getAttempt()} and throws on attempts 1 and 2, returning
 * the attempt number ({@code 3}) on the third. {@code after} depends on {@code flaky} and returns that value doubled
 * ({@code 6}), proving a retried task's result flows downstream normally. {@code maxConcurrency=1} for a deterministic
 * order.
 *
 * <p>The point of the scenario: {@code flaky} ends {@code SUCCEEDED} (not {@code FAILED}) and {@code after} runs rather
 * than being skipped — which is exactly what a broken retry inside a DAG would break. Returns
 * {@code {"flaky": 3, "after": 6}}.
 */
public class DagRetry extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        DagResult r = context.dag(
                "retrydag",
                d -> {
                    var flaky = d.step(
                            "flaky",
                            Integer.class,
                            (deps, s) -> {
                                // getAttempt() is 1-based at runtime: fail on attempts 1 and 2, succeed on 3.
                                if (s.getAttempt() < 3) {
                                    throw new RuntimeException("attempt " + s.getAttempt() + " is not yet the third");
                                }
                                return s.getAttempt();
                            },
                            StepConfig.builder()
                                    .retryStrategy(RetryStrategies.fixedDelay(3, Duration.ofSeconds(1)))
                                    .build());
                    d.step("after", Integer.class, (deps, s) -> deps.get(flaky) * 2)
                            .reads(flaky);
                },
                DagConfig.builder().maxConcurrency(1).build());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flaky", r.getResult("flaky").orElseThrow());
        out.put("after", r.getResult("after").orElseThrow());
        return out;
    }
}
