// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.otel;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.ExampleTemplate;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.otel.ExecutionOtelPlugin;

/**
 * OTel + X-Ray example using the ExecutionOtelPlugin with the no-arg constructor.
 *
 * <p>{@link ExecutionOtelPlugin#ExecutionOtelPlugin()} uses the global provider initialized by the ADOT Java agent. The
 * ExecutionOtelPlugin renders the Workflow span as the durable trace root with operations beneath it. Operations link
 * to the Invocation span in the ambient Lambda trace.
 */
@ExampleTemplate(tracing = true, javaAgent = true)
public class OtelXRayExecutionStepExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new ExecutionOtelPlugin()).build();
    }

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        context.getLogger().info("Starting OTel X-Ray execution view example for {}", input.getName());

        var greeting = context.step("exec-create-greeting", String.class, stepCtx -> "Hello, " + input.getName());

        var result = context.step("exec-transform", String.class, stepCtx -> greeting.toUpperCase() + "!");

        context.getLogger().info("OTel X-Ray execution view example complete: {}", result);
        return result;
    }
}
