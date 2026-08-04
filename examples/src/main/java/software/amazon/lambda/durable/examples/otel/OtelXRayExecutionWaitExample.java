// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.otel;

import java.time.Duration;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.ExampleTemplate;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.otel.ExecutionOtelPlugin;

/**
 * OTel + X-Ray example using ExecutionOtelPlugin with a step → wait → step pattern.
 *
 * <p>Exercises the multi-invocation tracing scenario with the workflow-rooted trace structure. The Workflow span is
 * only exported on the terminal invocation, producing a clean single-execution trace.
 */
@ExampleTemplate(tracing = true, javaAgent = true)
public class OtelXRayExecutionWaitExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new ExecutionOtelPlugin()).build();
    }

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        context.getLogger().info("Starting OTel X-Ray execution view wait example for {}", input.getName());

        var before = context.step("exec-before-wait", String.class, stepCtx -> "Prepared: " + input.getName());

        context.wait("exec-pause", Duration.ofSeconds(5));

        var after = context.step("exec-after-wait", String.class, stepCtx -> before + " | Resumed and completed");

        context.getLogger().info("OTel X-Ray execution view wait example complete: {}", after);
        return after;
    }
}
