// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.UserFunctionEndInfo;
import software.amazon.lambda.durable.plugin.UserFunctionStartInfo;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * 10-21: Attempt hook info field shape (CANONICAL DUMP).
 *
 * <p>A single step named {@code "flaky"} that throws on attempt 1 and succeeds on attempt 2 using the SDK's real
 * exponential-backoff retry strategy (max attempts 3, ~1s delay), returning {@code "ok"}. The instrumentation plugin
 * (filtering to step-type attempts) emits ONE single-line JSON record per per-attempt (user-function) hook event: a
 * canonical dump of that hook's OWN info parameter, every exposed component mapped to its canonical camelCase name,
 * null / unexposed fields OMITTED.
 *
 * <p>Java's {@link UserFunctionStartInfo} exposes id/name/type/subType/parentId/startTimestamp/isReplay/
 * isReplayingChildren/attempt (no endTimestamp/outcome/error at start). Java's {@link UserFunctionEndInfo} adds
 * endTimestamp, the {@code succeeded} boolean (presented as the shared {@code outcome} SUCCEEDED/FAILED token) and
 * {@code error}. {@code isReplay} is the operation-level replay indicator (this operation was present in the
 * checkpointed state delivered at invocation start); {@code isReplayingChildren} is the distinct context-children
 * indicator and is dumped unasserted here.
 */
@SuppressWarnings("deprecation")
public class PluginAttemptInfoShape extends DurableHandler<Object, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(new AttemptShapePlugin()).build();
    }

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.step(
                "flaky",
                String.class,
                stepCtx -> {
                    // Fail on the first attempt, succeed on the second, using the SDK's built-in 1-based attempt
                    // number.
                    if (stepCtx.getAttempt() < 2) {
                        throw new RuntimeException("Attempt " + stepCtx.getAttempt() + " failed");
                    }
                    return "ok";
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.exponentialBackoff(
                                3, Duration.ofSeconds(1), Duration.ofSeconds(10), 1.0, JitterStrategy.NONE))
                        .build());
    }

    private static final class AttemptShapePlugin implements DurableExecutionPlugin {
        private volatile String executionArn;

        @Override
        public void onInvocationStart(InvocationInfo info) {
            this.executionArn = info.durableExecutionArn();
        }

        @Override
        public void onUserFunctionStart(UserFunctionStartInfo info) {
            if (!PluginSupport.isStep(info.type()) || info.attempt() == null) {
                return;
            }
            new Rec("attempt-start")
                    .str("id", info.id())
                    .str("name", info.name())
                    .str("type", Rec.upper(info.type()))
                    .str("subType", info.subType())
                    .str("parentId", info.parentId())
                    .num("attempt", info.attempt())
                    .time("startTimestamp", info.startTimestamp())
                    .bool("isReplay", info.isReplay())
                    .bool("isReplayingChildren", info.isReplayingChildren())
                    .emit(executionArn);
        }

        @Override
        public void onUserFunctionEnd(UserFunctionEndInfo info) {
            if (!PluginSupport.isStep(info.type()) || info.attempt() == null) {
                return;
            }
            new Rec("attempt-end")
                    .str("id", info.id())
                    .str("name", info.name())
                    .str("type", Rec.upper(info.type()))
                    .str("subType", info.subType())
                    .str("parentId", info.parentId())
                    .num("attempt", info.attempt())
                    .time("startTimestamp", info.startTimestamp())
                    .time("endTimestamp", info.endTimestamp())
                    .bool("isReplay", info.isReplay())
                    .bool("isReplayingChildren", info.isReplayingChildren())
                    .str("outcome", info.succeeded() ? "SUCCEEDED" : "FAILED")
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
