// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class PluginLinkageIntegrationTest {
    @Test
    void incompatibleHooksLeaveHealthyPluginsAndReplayWorking() {
        var starts = new AtomicInteger();
        var stepCalls = new AtomicInteger();
        var ends = Collections.synchronizedList(new ArrayList<InvocationStatus>());
        var healthy = healthyPlugin(starts, ends);
        var config =
                DurableConfig.builder().withPlugins(brokenPlugin(), healthy).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    var saved = ctx.step("save", String.class, step -> {
                        stepCalls.incrementAndGet();
                        return input;
                    });
                    ctx.wait("pause", Duration.ofMinutes(1));
                    return saved;
                },
                config);

        assertEquals(ExecutionStatus.PENDING, runner.run("value").getStatus());
        runner.advanceTime();
        var result = runner.run("value");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("value", result.getResult(String.class));
        assertEquals(1, stepCalls.get(), "The completed step must not be repeated on resume");
        assertEquals(2, starts.get());
        assertEquals(List.of(InvocationStatus.PENDING, InvocationStatus.SUCCEEDED), ends);
        assertSame(
                healthy,
                config.getPluginRunner().getPlugins().get(1),
                "Existing plugin instances and their lifetime are retained");
    }

    private static DurableExecutionPlugin healthyPlugin(AtomicInteger starts, List<InvocationStatus> ends) {
        return new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                starts.incrementAndGet();
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                ends.add(info.invocationStatus());
            }
        };
    }

    private static DurableExecutionPlugin brokenPlugin() {
        return (DurableExecutionPlugin) Proxy.newProxyInstance(
                DurableExecutionPlugin.class.getClassLoader(),
                new Class<?>[] {DurableExecutionPlugin.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "incompatible plugin";
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> proxy == args[0];
                        };
                    }
                    throw new NoSuchMethodError("incompatible optional instrumentation API");
                });
    }
}
