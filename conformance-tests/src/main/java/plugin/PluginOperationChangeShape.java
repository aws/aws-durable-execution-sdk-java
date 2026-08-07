// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.time.Instant;
import java.util.Locale;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationChangeInfo;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * 10-22: Operation-change hook info field shape (CANONICAL DUMP).
 *
 * <p>A single step named {@code "greet"} returning the constant {@code "task-a"}. For each step-type operation in the
 * change info's updated-operations delta the instrumentation plugin emits ONE single-line JSON record: a canonical dump
 * of that DELTA ITEM's OWN field surface, plus the hook-level fields {@code executionArn} (from the change info),
 * {@code updatedOperationsCount}/{@code operationsCount} (map sizes) and the derived {@code inFullMap} := the same id
 * also appears in the info's full operations map. Null / unexposed fields are OMITTED.
 *
 * <p>Java's {@link OperationChangeItemInfo} exposes id/name/type/subType/parentId/startTimestamp/endTimestamp/error/
 * status but does NOT expose the checkpointed serialized result, the attempt number, or a replay indicator, so
 * {@code result}, {@code attempt} and {@code isReplay} are absent on each item — those omissions are the honest reds
 * the requirement produces.
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
            int updatedOperationsCount = info.updatedOperations().size();
            int operationsCount = info.operations().size();
            for (OperationChangeItemInfo item : info.updatedOperations().values()) {
                if (!PluginSupport.isStepChange(item.type())) {
                    continue;
                }
                new Rec("operation-change")
                        .str("executionArn", info.durableExecutionArn())
                        .num("updatedOperationsCount", updatedOperationsCount)
                        .num("operationsCount", operationsCount)
                        .bool("inFullMap", info.operations().containsKey(item.id()))
                        .str("id", item.id())
                        .str("name", item.name())
                        .str("type", Rec.upper(item.type()))
                        .str("subType", item.subType())
                        .str("parentId", item.parentId())
                        .str(
                                "status",
                                item.status() == null
                                        ? null
                                        : Rec.upper(item.status().toString()))
                        .time("startTimestamp", item.startTimestamp())
                        .time("endTimestamp", item.endTimestamp())
                        .str("error", Rec.msg(item.error()))
                        .emit(executionArn);
            }
        }
    }

    /** Single-line JSON record builder: emits every provided key, skipping nulls, then stamps durableExecutionArn. */
    private static final class Rec {
        private final StringBuilder sb = new StringBuilder("{");

        Rec(String hook) {
            raw("plugin", "\"CONFPLUGIN\"");
            raw("hook", "\"" + hook + "\"");
        }

        Rec str(String key, String value) {
            if (value != null) {
                raw(key, quote(value));
            }
            return this;
        }

        Rec num(String key, Integer value) {
            if (value != null) {
                raw(key, value.toString());
            }
            return this;
        }

        Rec bool(String key, boolean value) {
            raw(key, value ? "true" : "false");
            return this;
        }

        Rec time(String key, Instant value) {
            if (value != null) {
                raw(key, quote(value.toString()));
            }
            return this;
        }

        private void raw(String key, String jsonValue) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append('"').append(key).append("\": ").append(jsonValue);
        }

        void emit(String executionArn) {
            System.out.println(sb.append(PluginSupport.arnField(executionArn)).append('}'));
        }

        static String upper(String s) {
            return s == null ? null : s.toUpperCase(Locale.ROOT);
        }

        static String msg(Throwable t) {
            if (t == null) {
                return null;
            }
            return t.getMessage() != null ? t.getMessage() : t.toString();
        }

        static String quote(String s) {
            StringBuilder b = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':
                        b.append("\\\"");
                        break;
                    case '\\':
                        b.append("\\\\");
                        break;
                    case '\n':
                        b.append("\\n");
                        break;
                    case '\r':
                        b.append("\\r");
                        break;
                    case '\t':
                        b.append("\\t");
                        break;
                    default:
                        if (c < 0x20) {
                            b.append(String.format("\\u%04x", (int) c));
                        } else {
                            b.append(c);
                        }
                }
            }
            return b.append('"').toString();
        }
    }
}
