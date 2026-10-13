// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PluginLinkageErrorTest {
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("linkageFailures")
    void linkageFailureDoesNotPreventTheNextPlugin(
            String hook, String failureName, Consumer<PluginRunner> dispatch, Error failure) {
        var healthyCalls = new AtomicInteger();
        var runner = new PluginRunner(List.of(
                plugin(() -> {
                    throw failure;
                }),
                plugin(healthyCalls::incrementAndGet)));

        assertDoesNotThrow(() -> dispatch.accept(runner));
        assertEquals(1, healthyCalls.get(), "The next plugin must still receive the hook");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("otherErrors")
    void otherErrorsRetainTheirExistingPropagation(
            String hook, String failureName, Consumer<PluginRunner> dispatch, Error failure) {
        var healthyCalls = new AtomicInteger();
        var runner = new PluginRunner(List.of(
                plugin(() -> {
                    throw failure;
                }),
                plugin(healthyCalls::incrementAndGet)));

        assertSame(failure, assertThrows(Error.class, () -> dispatch.accept(runner)));
        assertEquals(
                hook.equals("invocation end") ? 1 : 0,
                healthyCalls.get(),
                "Invocation End must finish cleanup before propagating errors; other hooks stop immediately");
    }

    static Stream<Arguments> linkageFailures() {
        return hooks().flatMap(hook -> Stream.of(
                        new NoSuchMethodError("old API"),
                        new AbstractMethodError("old implementation"),
                        new NoClassDefFoundError("missing dependency"),
                        new ExceptionInInitializerError("dependency initialization"))
                .map(error -> Arguments.of(hook.name(), error.getClass().getSimpleName(), hook.dispatch(), error)));
    }

    static Stream<Arguments> otherErrors() {
        return hooks().flatMap(hook -> Stream.of(
                        new InternalError("fatal JVM failure"), new ThreadDeath(), new AssertionError("unchanged"))
                .map(error -> Arguments.of(hook.name(), error.getClass().getSimpleName(), hook.dispatch(), error)));
    }

    private static Stream<Hook> hooks() {
        return Stream.of(
                new Hook("invocation start", runner -> runner.onInvocationStart(null)),
                new Hook("invocation end", runner -> runner.onInvocationEnd(null)),
                new Hook("operation start", runner -> runner.onOperationStart(null)),
                new Hook("operation end", runner -> runner.onOperationEnd(null)),
                new Hook("operation change", runner -> runner.onOperationChange(null)),
                new Hook("user function start", runner -> runner.onUserFunctionStart(null)),
                new Hook("user function end", runner -> runner.onUserFunctionEnd(null)));
    }

    private static DurableExecutionPlugin plugin(Runnable action) {
        return (DurableExecutionPlugin) Proxy.newProxyInstance(
                DurableExecutionPlugin.class.getClassLoader(),
                new Class<?>[] {DurableExecutionPlugin.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "test plugin";
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> proxy == args[0];
                        };
                    }
                    action.run();
                    return null;
                });
    }

    private record Hook(String name, Consumer<PluginRunner> dispatch) {}
}
