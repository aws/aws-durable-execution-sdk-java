// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.insight;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.insight.ContentConfig;
import software.amazon.lambda.durable.insight.WorkflowInsight;
import software.amazon.lambda.durable.insight.WorkflowInsightConfig;
import software.amazon.lambda.durable.insight.exporters.LambdaLogExporter;

/**
 * Example demonstrating the Workflow Insight plugin with zero extra infrastructure.
 *
 * <p>The plugin is registered in {@link #createConfiguration()} with a {@link LambdaLogExporter}, which writes one
 * curated {@code WorkflowInsight} JSON record to {@code stdout} at the end of each execution. On Lambda, {@code stdout}
 * is captured to the function's own CloudWatch Logs group, so no bucket, extra log group, or additional IAM permission
 * is required — the managed function log group is the destination.
 *
 * <p>Configuration used here:
 *
 * <ul>
 *   <li>{@code ON_COMPLETE} — emit exactly one record when the execution reaches a terminal state.
 *   <li>{@code TOP_LEVEL} — include only top-level operations (no nested child/map/parallel entries).
 *   <li>input, output, and errors all included in the record.
 * </ul>
 *
 * <p>The handler itself runs two named steps ({@code create-greeting} and {@code transform}) and returns a
 * {@code HELLO, <NAME>!} greeting.
 */
public class WorkflowInsightExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected DurableConfig createConfiguration() {
        var insight = WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                // Write the record to stdout -> the function's own CloudWatch Logs group (no extra infrastructure).
                .addExporter(new LambdaLogExporter())
                // Emit a single record when the execution completes.
                .emitMode(WorkflowInsightConfig.EmitMode.ON_COMPLETE)
                // Summarize only top-level operations.
                .operationDetail(WorkflowInsightConfig.OperationDetail.TOP_LEVEL)
                // Include the execution input, output, and any errors in the record.
                .content(ContentConfig.builder()
                        .input(true)
                        .output(true)
                        .includeErrors(true)
                        .build())
                .build());

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
