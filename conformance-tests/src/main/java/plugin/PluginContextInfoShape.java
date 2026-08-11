// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;
import software.amazon.lambda.durable.plugin.UserFunctionStartInfo;

/**
 * 10-23: Context-typed hook info field shape (CANONICAL DUMP).
 *
 * <p>A parallel operation named {@code "ctx"} with max-concurrency 1 and two branches: branch A runs a step named
 * {@code "inner"} returning {@code "x"}, then a 2-second wait, and returns {@code "a-done"}; branch B returns
 * {@code "b-done"} directly. With max-concurrency 1 branch A runs live, suspends on the wait, and re-runs on the replay
 * (replaying its checkpointed children) before branch B runs live — so the children-replay indicator flips true on
 * branch A's second {@code fn-start}.
 *
 * <p>The instrumentation plugin (filtering to CONTEXT-type operations) emits ONE single-line JSON record per hook
 * event: a canonical camelCase dump of that hook's OWN info parameter, null / unset fields OMITTED.
 *
 * <p>Java's {@link OperationInfo} (operation-start) exposes id/name/type/subType/parentId/startTimestamp/endTimestamp/
 * status/isReplay. Java's {@link UserFunctionStartInfo} (fn-start) exposes id/name/type/subType/parentId/
 * startTimestamp/isReplayingChildren/attempt — for CONTEXT operations {@code attempt} is null and is omitted. Only
 * fn-start is probed; attempt-end hooks are out of scope for a suspending context run.
 */
@SuppressWarnings("deprecation")
public class PluginContextInfoShape extends DurableHandler<Object, List<String>> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new ContextShapePlugin()).build();
    }

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder().maxConcurrency(1).build();
        var futures = new ArrayList<DurableFuture<String>>();
        ParallelDurableFuture parallel = context.parallel("ctx", config);
        try (parallel) {
            futures.add(parallel.branch("branch-a", String.class, branch -> {
                branch.step("inner", String.class, stepCtx -> "x");
                branch.wait(null, Duration.ofSeconds(2));
                return "a-done";
            }));
            futures.add(parallel.branch("branch-b", String.class, branch -> "b-done"));
        }
        return futures.stream().map(DurableFuture::get).toList();
    }

    private static final class ContextShapePlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
        }

        @Override
        public void onOperationStart(OperationInfo info) {
            if (!PluginSupport.isContext(info.type())) {
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
        public void onUserFunctionStart(UserFunctionStartInfo info) {
            if (!PluginSupport.isContext(info.type())) {
                return;
            }
            new Rec("fn-start")
                    .str("id", info.id())
                    .str("name", info.name())
                    .str("type", Rec.upper(info.type()))
                    .str("subType", info.subType())
                    .str("parentId", info.parentId())
                    .num("attempt", info.attempt())
                    .time("startTimestamp", info.startTimestamp())
                    .bool("isReplayingChildren", info.isReplayingChildren())
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
