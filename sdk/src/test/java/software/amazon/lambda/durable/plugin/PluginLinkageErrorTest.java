// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Instant;
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
        var runner = runner(hook, failure, healthyCalls);

        assertDoesNotThrow(() -> dispatch.accept(runner));
        assertEquals(1, healthyCalls.get(), "The next plugin must still receive the hook");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("fatalErrors")
    void fatalErrorsRetainTheirIdentity(
            String hook, String failureName, Consumer<PluginRunner> dispatch, Error failure) {
        var healthyCalls = new AtomicInteger();
        var runner = runner(hook, failure, healthyCalls);

        assertSame(failure, assertThrows(Error.class, () -> dispatch.accept(runner)));
        assertEquals(
                hook.equals("onInvocationEnd") || hook.equals("onUserFunctionEnd") ? 1 : 0,
                healthyCalls.get(),
                "End hooks still give every plugin its cleanup opportunity; other fatal hooks stop dispatch");
    }

    static Stream<Arguments> linkageFailures() {
        return hooks().flatMap(hook -> Stream.of(
                        new NoSuchMethodError("old API"),
                        new AbstractMethodError("old implementation"),
                        new NoClassDefFoundError("missing dependency"),
                        new ExceptionInInitializerError("dependency initialization"))
                .map(error -> Arguments.of(hook.name(), error.getClass().getSimpleName(), hook.dispatch(), error)));
    }

    static Stream<Arguments> fatalErrors() {
        return hooks().flatMap(hook -> Stream.of(new InternalError("fatal JVM failure"), new ThreadDeath())
                .map(error -> Arguments.of(hook.name(), error.getClass().getSimpleName(), hook.dispatch(), error)));
    }

    private static Stream<Hook> hooks() {
        return Stream.of(
                new Hook("onInvocationStart", runner -> runner.onInvocationStart(invocationInfo())),
                new Hook(
                        "onInvocationEnd",
                        runner -> runner.onInvocationEnd(new InvocationEndInfo(
                                "request", "arn:execution", true, InvocationStatus.SUCCEEDED, null))),
                new Hook("onOperationStart", runner -> runner.onOperationStart(null)),
                new Hook("onOperationEnd", runner -> runner.onOperationEnd(null)),
                new Hook("onOperationChange", runner -> runner.onOperationChange(null)),
                new Hook(
                        "onUserFunctionStart",
                        runner -> runner.onUserFunctionStart(new UserFunctionStartInfo(
                                "step", "step", "STEP", null, null, Instant.EPOCH, false, 1))),
                new Hook("onUserFunctionEnd", runner -> runner.onUserFunctionEnd(null)));
    }

    private static InvocationInfo invocationInfo() {
        return new InvocationInfo("request", "arn:execution", true, Instant.EPOCH);
    }

    private static PluginRunner runner(String hook, Error failure, AtomicInteger healthyCalls) {
        var runner = new PluginRunner(List.of(
                info -> plugin(hook, () -> {
                    throw failure;
                }),
                info -> plugin(hook, healthyCalls::incrementAndGet)));
        if (!hook.equals("onInvocationStart")) runner.onInvocationStart(invocationInfo());
        return runner;
    }

    private static DurableExecutionPlugin plugin(String hook, Runnable action) {
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
                    if (method.getName().equals(hook)) action.run();
                    return null;
                });
    }

    private record Hook(String name, Consumer<PluginRunner> dispatch) {}
}
