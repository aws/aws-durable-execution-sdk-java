// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.parallel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.model.ParallelResult;

/**
 * Example demonstrating parallel branches where some branches include wait operations.
 *
 * <p>This models a notification fan-out pattern where different channels have different delivery delays:
 *
 * <ul>
 *   <li>Email — sent immediately
 *   <li>SMS — waits for a rate-limit window before sending
 *   <li>Push notification — waits for a quiet-hours window before sending
 * </ul>
 *
 * <p>All three branches run concurrently. Branches with waits suspend without consuming compute resources and resume
 * automatically once the wait elapses. The parallel operation completes once all branches finish.
 */
public class ParallelWithWaitExample
        extends DurableHandler<ParallelWithWaitExample.Input, ParallelWithWaitExample.Output> {

    public record Input(String userId, String message) {}

    public record Output(List<String> deliveries, int success, int faiure) {}

    @Override
    public Output handleRequest(Input input, DurableContext context) {
        var logger = context.getLogger();
        logger.info("Sending notifications to user {}", input.userId());

        var config = ParallelConfig.builder().build();
        var futures = new ArrayList<DurableFuture<String>>(3);
        var parallel = context.parallel("notify", config);

        try (parallel) {

            // Branch 1: email — no wait, deliver immediately
            futures.add(parallel.branch("email", String.class, ctx -> {
                ctx.wait("email-rate-limit-delay", Duration.ofSeconds(10));
                return ctx.step("send-email", String.class, stepCtx -> "email:" + input.message());
            }));

            // Branch 2: SMS — wait for rate-limit window, then send
            futures.add(parallel.branch("sms", String.class, ctx -> {
                ctx.wait("sms-rate-limit-delay", Duration.ofSeconds(10));
                return ctx.step("send-sms", String.class, stepCtx -> "sms:" + input.message());
            }));

            // Branch 3: push notification — wait for quiet-hours window, then send
            futures.add(parallel.branch("push", String.class, ctx -> {
                ctx.wait("push-quiet-delay", Duration.ofSeconds(10));
                return ctx.step("send-push", String.class, stepCtx -> "push:" + input.message());
            }));
        }

        ParallelResult result = parallel.get();

        var deliveries = futures.stream().map(DurableFuture::get).toList();
        logger.info("All {} notifications delivered", deliveries.size());
        // Test replay
        context.wait("wait for finalization", Duration.ofSeconds(5));
        return new Output(deliveries, result.succeeded(), result.failed());
    }
}
