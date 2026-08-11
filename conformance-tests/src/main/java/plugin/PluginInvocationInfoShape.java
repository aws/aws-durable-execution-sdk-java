// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package plugin;

import java.time.Duration;
import java.time.Instant;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;

/**
 * 10-19: Invocation hook info field shape (CANONICAL DUMP).
 *
 * <p>A single 2-second wait that then returns {@code "done-" + input}. The instrumentation plugin emits ONE single-line
 * JSON record per invocation hook event: a canonical dump of that hook's OWN info parameter, every exposed component
 * mapped one-to-one to its canonical camelCase name, null / unexposed fields OMITTED (a missing key fails its assertion
 * — the parity signal).
 *
 * <p>Java's {@link InvocationInfo} exposes {@code requestId}, {@code executionStartTime} (→
 * {@code executionStartTimestamp}), {@code isFirstInvocation} and the {@code operations} / {@code updatedOperations}
 * maps (dumped as {@code operationsCount} / {@code updatedOperationsCount}); it does NOT expose the execution input, so
 * {@code executionInput} is absent. Java's {@link InvocationEndInfo} carries the same identity surface plus
 * {@code invocationStatus} (→ {@code status}) and {@code executionError}; it does NOT expose the execution input or the
 * final result, so {@code executionInput} and {@code executionResult} are absent. The single derived scalar
 * {@code terminal} := status in (SUCCEEDED, FAILED). Those payload omissions are deliberate — payload surfaces are out
 * of GA scope.
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
            new Rec("invocation-start")
                    .bool("isFirstInvocation", info.isFirstInvocation())
                    .str("requestId", info.requestId())
                    .num("operationsCount", info.operations().size())
                    .num("updatedOperationsCount", info.updatedOperations().size())
                    .time("executionStartTimestamp", info.executionStartTime())
                    .emit(executionArn);
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            InvocationStatus status = info.invocationStatus();
            boolean terminal = status == InvocationStatus.SUCCEEDED || status == InvocationStatus.FAILED;
            new Rec("invocation-end")
                    .bool("isFirstInvocation", info.isFirstInvocation())
                    .str("requestId", info.requestId())
                    .num("operationsCount", info.operations().size())
                    .time("executionStartTimestamp", info.executionStartTime())
                    .str("status", status == null ? null : status.name())
                    .bool("terminal", terminal)
                    .str("executionError", Rec.msg(info.executionError()))
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
