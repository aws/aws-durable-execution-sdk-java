// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.util.Locale;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;

/**
 * 10-20: Operation hook info field shape.
 *
 * <p>A single step named {@code "greet"} returning the constant {@code "task-a"}. INTERFACE-SHAPE probe filtering to
 * step-type operations: every logged field is read from the CURRENT hook's own info parameter only. Java's
 * {@link OperationInfo} carries identity, {@code startTimestamp}, {@code status}, and {@code isReplay}; at
 * operation-start {@code has_status} is emitted for observability but not asserted. Java's {@link OperationEndInfo}
 * carries {@code status}, {@code attempt}, {@code endTimestamp}, and {@code error} but does NOT expose the operation's
 * checkpointed serialized result, so {@code has_result} is honestly false and the {@code result} value key is omitted —
 * that omission is the parity signal the requirement exists to produce.
 */
@SuppressWarnings("deprecation")
public class PluginOperationInfoShape extends DurableHandler<Object, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new OperationShapePlugin()).build();
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step("greet", String.class, stepCtx -> "task-a");
    }

    private static final class OperationShapePlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
        }

        @Override
        public void onOperationStart(OperationInfo info) {
            if (!PluginSupport.isStep(info.type())) {
                return;
            }
            boolean hasStartTime = info.startTimestamp() != null;
            boolean hasStatus = info.status() != null; // emitted for observability, not asserted at start
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"operation-start\", \"op\": \"%s\", \"name\": \"%s\", "
                            + "\"type\": \"%s\", \"replay\": %b, \"has_start_time\": %b, \"has_status\": %b%s}",
                    info.id(),
                    info.name(),
                    info.type().toUpperCase(Locale.ROOT),
                    info.isReplay(),
                    hasStartTime,
                    hasStatus,
                    PluginSupport.arnField(executionArn)));
        }

        @Override
        public void onOperationEnd(OperationEndInfo info) {
            if (!PluginSupport.isStep(info.type())) {
                return;
            }
            boolean hasResult = false; // no checkpointed-result accessor on OperationEndInfo; result key omitted
            boolean hasError = info.error() != null;
            boolean hasEndTime = info.endTimestamp() != null;
            System.out.println(String.format(
                    "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"operation-end\", \"op\": \"%s\", \"name\": \"%s\", "
                            + "\"type\": \"%s\", \"replay\": %b, \"status\": \"%s\", \"has_result\": %b, "
                            + "\"has_error\": %b, \"attempt\": %s, \"has_end_time\": %b%s}",
                    info.id(),
                    info.name(),
                    info.type().toUpperCase(Locale.ROOT),
                    info.isReplay(),
                    info.status(),
                    hasResult,
                    hasError,
                    info.attempt(),
                    hasEndTime,
                    PluginSupport.arnField(executionArn)));
        }
    }
}
