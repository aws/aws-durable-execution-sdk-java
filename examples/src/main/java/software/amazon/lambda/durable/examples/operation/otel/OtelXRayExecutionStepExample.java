// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.otel;

import static software.amazon.lambda.durable.logging.DurableLogger.getLogger;
import static software.amazon.lambda.durable.operation.DurableStepOperation.step;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.ExampleTemplate;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.otel.ExecutionOtelPlugin;

/**
 * OTel + X-Ray example using the ExecutionOtelPlugin with the no-arg constructor.
 *
 * <p>{@link ExecutionOtelPlugin#ExecutionOtelPlugin()} uses the global provider initialized by the ADOT Java agent. The
 * ExecutionOtelPlugin renders the Workflow span as the trace root with operations as siblings of the invocation span.
 */
@ExampleTemplate(tracing = true, javaAgent = true)
public class OtelXRayExecutionStepExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new ExecutionOtelPlugin()).build();
    }

    @Override
    public String handleRequest(GreetingRequest input) {
        getLogger().info("Starting OTel X-Ray execution view example for {}", input.getName());

        var greeting = step("exec-create-greeting", String.class, () -> "Hello, " + input.getName());

        var result = step("exec-transform", String.class, () -> greeting.toUpperCase() + "!");

        getLogger().info("OTel X-Ray execution view example complete: {}", result);
        return result;
    }
}
