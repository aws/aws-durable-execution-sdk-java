// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.time.Duration;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;

/**
 * 10-19: Invocation hook info field shape.
 *
 * <p>A single 2-second wait that then returns {@code "done-" + input}. INTERFACE-SHAPE probe: every logged field is
 * read from the CURRENT hook's own info parameter only — never reconstructed from another hook or from plugin state.
 * Java's {@link InvocationInfo} exposes {@code requestId} and {@code executionStartTime} but does NOT expose the
 * execution input, the execution operations map, or an externally-updated-operations collection; those are honestly
 * emitted as {@code has_*: false} with the value key omitted. Likewise {@link InvocationEndInfo} exposes
 * {@code invocationStatus} and {@code executionError} but not the execution's final result, so {@code has_result} is
 * honestly false. Those omissions are the parity signals the requirement exists to produce.
 */
@SuppressWarnings("deprecation")
public class PluginInvocationInfoShape extends DurableHandler<String, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new InvocationShapePlugin()).build();
    }

    @Override
    public String handleRequest(String input, DurableContext context) {
        context.wait(null, Duration.ofSeconds(2));
        return "done-" + input;
    }

    private static final class InvocationShapePlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
            boolean hasRequestId = info.requestId() != null;
            boolean hasInput = false; // no execution-input accessor on InvocationInfo
            boolean hasOperations = false; // no operations map on InvocationInfo
            boolean updatedNonempty = false; // no externally-updated-operations collection on InvocationInfo
            boolean hasStartTime = info.executionStartTime() != null;
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"invocation-start\", \"first\": %b, "
                            + "\"has_request_id\": %b, \"has_input\": %b, \"has_operations\": %b, "
                            + "\"updated_nonempty\": %b, \"has_start_time\": %b%s}",
                    info.isFirstInvocation(),
                    hasRequestId,
                    hasInput,
                    hasOperations,
                    updatedNonempty,
                    hasStartTime,
                    PluginSupport.arnField(executionArn)));
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            InvocationStatus status = info.invocationStatus();
            // terminal := status in (SUCCEEDED, FAILED); first is read from the END info parameter itself.
            boolean terminal = status == InvocationStatus.SUCCEEDED || status == InvocationStatus.FAILED;
            boolean hasResult = false; // no final-result accessor on InvocationEndInfo
            boolean hasError = info.executionError() != null;
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"invocation-end\", \"first\": %b, \"terminal\": %b, "
                            + "\"status\": \"%s\", \"has_result\": %b, \"has_error\": %b%s}",
                    info.isFirstInvocation(),
                    terminal,
                    status.name(),
                    hasResult,
                    hasError,
                    PluginSupport.arnField(executionArn)));
        }
    }
}
