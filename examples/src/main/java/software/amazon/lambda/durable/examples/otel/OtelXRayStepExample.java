// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.otel;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.ExampleTemplate;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.otel.OtelPlugin;

/**
 * OTel + X-Ray example: simple steps in a single invocation.
 *
 * <p>Exports spans through the ADOT Java agent global OpenTelemetry provider. Requires:
 *
 * <ul>
 *   <li>{@code Tracing: Active} on the Lambda function
 *   <li>ADOT Lambda Layer added to the function
 *   <li>{@code OTEL_JAVAAGENT_EXTENSIONS} pointing at the OTel plugin jar
 * </ul>
 *
 * <p>Expected trace structure in X-Ray:
 *
 * <pre>
 * invocation
 * ├── create-greeting
 * │   └── create-greeting attempt 1
 * └── transform
 *     └── transform attempt 1
 * </pre>
 */
@ExampleTemplate(tracing = true, javaAgent = true)
public class OtelXRayStepExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new OtelPlugin()).build();
    }

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        context.getLogger().info("Starting OTel X-Ray step example for {}", input.getName());

        var greeting = context.step("create-greeting", String.class, stepCtx -> "Hello, " + input.getName());

        var result = context.step("transform", String.class, stepCtx -> greeting.toUpperCase() + "!");

        context.getLogger().info("OTel X-Ray step example complete: {}", result);
        return result;
    }
}
