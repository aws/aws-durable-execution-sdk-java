// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.general;

import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.plugin.*;

/**
 * Example demonstrating plugin instrumentation with the Durable Execution SDK.
 *
 * <p>This handler registers a simple logging plugin that prints lifecycle events to stdout (which appears in CloudWatch
 * Logs when deployed). Deploy this and check CloudWatch to verify all hooks fire at the right times.
 *
 * <p>Expected output for a successful run:
 *
 * <pre>
 * [PLUGIN] onInvocationStart: requestId=..., executionArn=..., firstInvocation=true
 * [PLUGIN] onOperationStart: name=create-greeting, type=STEP
 * [PLUGIN] onUserFunctionStart: name=create-greeting, attempt=1
 * [PLUGIN] onUserFunctionEnd: name=create-greeting, succeeded=true
 * [PLUGIN] onOperationEnd: name=create-greeting
 * [PLUGIN] onOperationStart: name=transform, type=STEP
 * [PLUGIN] onUserFunctionStart: name=transform, attempt=1
 * [PLUGIN] onUserFunctionEnd: name=transform, succeeded=true
 * [PLUGIN] onOperationEnd: name=transform
 * [PLUGIN] onInvocationEnd: status=SUCCEEDED
 * </pre>
 */
public class PluginExample extends DurableHandler<GreetingRequest, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new LoggingPlugin()).build();
    }

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        context.getLogger().info("Starting plugin example for {}", input.getName());

        var greeting = context.step("create-greeting", String.class, stepCtx -> "Hello, " + input.getName());

        var result = context.step("transform", String.class, stepCtx -> greeting.toUpperCase() + "!");

        context.getLogger().info("Plugin example complete: {}", result);
        return result;
    }

    /** A simple plugin that logs all lifecycle events to stdout. In Lambda, stdout goes to CloudWatch Logs. */
    static class LoggingPlugin implements DurableExecutionPlugin {

        @Override
        public void onInvocationStart(InvocationInfo info) {
            System.out.printf(
                    "[PLUGIN] onInvocationStart: requestId=%s, executionArn=%s, firstInvocation=%s%n",
                    info.requestId(), info.executionArn(), info.isFirstInvocation());
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            System.out.printf(
                    "[PLUGIN] onInvocationEnd: status=%s, error=%s%n",
                    info.invocationStatus(),
                    info.executionError() != null ? info.executionError().getMessage() : null);
        }

        @Override
        public void onOperationStart(OperationInfo info) {
            System.out.printf(
                    "[PLUGIN] onOperationStart: name=%s, type=%s, id=%s%n", info.name(), info.type(), info.id());
        }

        @Override
        public void onOperationEnd(OperationEndInfo info) {
            System.out.printf(
                    "[PLUGIN] onOperationEnd: name=%s, type=%s, error=%s%n",
                    info.name(),
                    info.type(),
                    info.error() != null ? info.error().getMessage() : null);
        }

        @Override
        public void onUserFunctionStart(UserFunctionStartInfo info) {
            System.out.printf(
                    "[PLUGIN] onUserFunctionStart: name=%s, type=%s, attempt=%s, isReplayingChildren=%s%n",
                    info.name(), info.type(), info.attempt(), info.isReplayingChildren());
        }

        @Override
        public void onUserFunctionEnd(UserFunctionEndInfo info) {
            System.out.printf(
                    "[PLUGIN] onUserFunctionEnd: name=%s, succeeded=%s, error=%s%n",
                    info.name(),
                    info.succeeded(),
                    info.error() != null ? info.error().getMessage() : null);
        }
    }
}
