// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.util.Locale;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationChangeInfo;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * 10-22: Operation-change hook info field shape.
 *
 * <p>A single step named {@code "greet"} returning the constant {@code "task-a"}. INTERFACE-SHAPE probe of the
 * operation-change hook: every logged field is read from the CURRENT hook's own info parameter only. For each step-type
 * operation in the change info's updated-operations delta the plugin reports the operation id, its post-change status,
 * whether the same id is present in the info's full operations map, whether the change info itself carries the
 * execution ARN, and the DELTA ITEM's own field surface. Java's {@link OperationChangeItemInfo} carries identity,
 * {@code startTimestamp}, {@code endTimestamp}, {@code error}, and {@code status}, but does NOT expose the checkpointed
 * serialized result, the attempt number, or a replay indicator — so {@code item_has_result}, {@code item_has_attempt},
 * and {@code item_has_replay} are honestly false. Those omissions are the parity signals the requirement exists to
 * produce.
 */
@SuppressWarnings("deprecation")
public class PluginOperationChangeShape extends DurableHandler<Object, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new ChangeShapePlugin()).build();
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step("greet", String.class, stepCtx -> "task-a");
    }

    private static final class ChangeShapePlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
        }

        @Override
        public void onOperationChange(OperationChangeInfo info) {
            for (OperationChangeItemInfo item : info.updatedOperations().values()) {
                if (!PluginSupport.isStepChange(item.type())) {
                    continue;
                }
                boolean inFullMap = info.operations().containsKey(item.id());
                boolean hasArn = info.durableExecutionArn() != null;
                String status = item.status() != null ? item.status().toString() : "NONE";
                boolean itemHasResult = false; // no serialized-result accessor on OperationChangeItemInfo
                boolean itemHasEndTime = item.endTimestamp() != null;
                boolean itemHasAttempt = false; // no attempt accessor on OperationChangeItemInfo
                boolean itemHasReplay = false; // no replay-indicator accessor on OperationChangeItemInfo
                System.out.println(String.format(
                        "{\"plugin\": \"CONFPLUGIN\", \"hook\": \"operation-change\", \"op\": \"%s\", "
                                + "\"status\": \"%s\", \"in_full_map\": %b, \"has_arn\": %b, \"item_name\": \"%s\", "
                                + "\"item_type\": \"%s\", \"item_has_result\": %b, \"item_has_end_time\": %b, "
                                + "\"item_has_attempt\": %b, \"item_has_replay\": %b%s}",
                        item.id(),
                        status,
                        inFullMap,
                        hasArn,
                        item.name(),
                        item.type().toUpperCase(Locale.ROOT),
                        itemHasResult,
                        itemHasEndTime,
                        itemHasAttempt,
                        itemHasReplay,
                        PluginSupport.arnField(executionArn)));
            }
        }
    }
}
