// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.otel;

import java.time.Duration;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.ExampleTemplate;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.otel.OtelPlugin;

/**
 * OTel + X-Ray example: step → wait → step pattern that forces multiple Lambda invocations.
 *
 * <p>This handler exercises the critical multi-invocation tracing scenario:
 *
 * <ol>
 *   <li>Invocation 1: "before-wait" step completes → wait suspends execution
 *   <li>Invocation 2: replays "before-wait" (no-op) → wait completes → "after-wait" step runs
 * </ol>
 *
 * <p>Exports spans through the ADOT Java agent global OpenTelemetry provider. Requires:
 *
 * <ul>
 *   <li>{@code Tracing: Active} on the Lambda function
 *   <li>ADOT Lambda Layer added to the function
 *   <li>{@code OTEL_JAVAAGENT_EXTENSIONS} pointing at the OTel plugin jar
 * </ul>
 *
 * <p>Expected trace structure in X-Ray (all under one trace ID — backend propagates same Root):
 *
 * <pre>
 * Trace (single trace ID across both invocations)
 * ├── durable.invocation (invocation 1)
 * │   ├── durable.step:before-wait
 * │   │   └── durable.step:before-wait [attempt 1]
 * │   └── durable.wait:pause (ended as PENDING)
 * └── durable.invocation (invocation 2)
 *     ├── durable.wait:pause (completed)
 *     └── durable.step:after-wait
 *         └── durable.step:after-wait [attempt 1]
 * </pre>
 */
@ExampleTemplate(tracing = true, javaAgent = true)
public class OtelXRayWaitExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new OtelPlugin()).build();
    }

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        context.getLogger().info("Starting OTel X-Ray wait example for {}", input.getName());

        var before = context.step("before-wait", String.class, stepCtx -> "Prepared: " + input.getName());

        // This wait forces Lambda to suspend and re-invoke after the duration
        context.wait("pause", Duration.ofSeconds(5));

        var after = context.step("after-wait", String.class, stepCtx -> before + " | Resumed and completed");

        context.getLogger().info("OTel X-Ray wait example complete: {}", after);
        return after;
    }
}
