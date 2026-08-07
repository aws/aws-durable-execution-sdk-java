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
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;

/**
 * 10-20: Operation hook info field shape (CANONICAL DUMP).
 *
 * <p>A single step named {@code "greet"} returning the constant {@code "task-a"}. The instrumentation plugin (filtering
 * to step-type operations) emits ONE single-line JSON record per operation hook event: a canonical dump of that hook's
 * OWN info parameter, every exposed component mapped to its canonical camelCase name, null / unexposed fields OMITTED.
 *
 * <p>Java's {@link OperationInfo} (operation-start) exposes id/name/type/subType/parentId/startTimestamp/endTimestamp/
 * status/isReplay; at a LIVE first start {@code status}/{@code startTimestamp} may be unset and are simply omitted, and
 * the record has no {@code attempt}/{@code result}/{@code error} at all. Java's {@link OperationEndInfo}
 * (operation-end) adds {@code attempt} and {@code error} but does NOT expose the checkpointed serialized result, so
 * {@code result} is absent on the end record — that omission is the honest red the requirement produces.
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
            new Rec("operation-start")
                    .str("id", info.id())
                    .str("name", info.name())
                    .str("type", Rec.upper(info.type()))
                    .str("subType", info.subType())
                    .str("parentId", info.parentId())
                    .str("status", Rec.upper(info.status()))
                    .time("startTimestamp", info.startTimestamp())
                    .time("endTimestamp", info.endTimestamp())
                    .bool("isReplay", info.isReplay())
                    .emit(executionArn);
        }

        @Override
        public void onOperationEnd(OperationEndInfo info) {
            if (!PluginSupport.isStep(info.type())) {
                return;
            }
            new Rec("operation-end")
                    .str("id", info.id())
                    .str("name", info.name())
                    .str("type", Rec.upper(info.type()))
                    .str("subType", info.subType())
                    .str("parentId", info.parentId())
                    .str("status", Rec.upper(info.status()))
                    .time("startTimestamp", info.startTimestamp())
                    .time("endTimestamp", info.endTimestamp())
                    .num("attempt", info.attempt())
                    .bool("isReplay", info.isReplay())
                    .str("error", Rec.msg(info.error()))
                    .emit(executionArn);
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
