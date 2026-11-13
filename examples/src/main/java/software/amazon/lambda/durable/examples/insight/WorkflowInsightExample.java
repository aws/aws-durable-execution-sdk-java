// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.insight;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.insight.WorkflowInsight;
import software.amazon.lambda.durable.insight.WorkflowInsightConfig;

/**
 * Example demonstrating the Workflow Insight plugin with zero extra infrastructure and its default configuration.
 *
 * <p>The empty {@link WorkflowInsightConfig} uses the default Lambda log exporter, which writes one curated
 * {@code WorkflowInsight} JSON record to {@code stdout} when the execution completes. It includes top-level operations,
 * input, output, and errors. On Lambda, {@code stdout} is captured by the function's own CloudWatch Logs group, so no
 * bucket, extra log group, or additional IAM permission is required.
 *
 * <p>The handler itself runs two named steps ({@code create-greeting} and {@code transform}) and returns a
 * {@code HELLO, <NAME>!} greeting.
 */
public class WorkflowInsightExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected DurableConfig createConfiguration() {
        var insight =
                WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder().build());
        return DurableConfig.builder().withPlugins(insight).build();
    }

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        context.getLogger().info("Building greeting for {}", input.getName());

        // Step 1: create the greeting.
        var greeting = context.step("create-greeting", String.class, stepCtx -> "Hello, " + input.getName());

        // Step 2: transform it into the final HELLO, <NAME>! form.
        var result = context.step("transform", String.class, stepCtx -> greeting.toUpperCase() + "!");

        context.getLogger().info("Workflow Insight example complete: {}", result);
        return result;
    }
}
