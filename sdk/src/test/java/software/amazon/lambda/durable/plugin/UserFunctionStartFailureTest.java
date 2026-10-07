// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class UserFunctionStartFailureTest {
    @SuppressWarnings("removal")
    static Stream<Arguments> failures() {
        return Stream.of(false, true)
                .flatMap(wrapped -> Stream.of(new InternalError("start hook"), new ThreadDeath())
                        .map(fatal -> Arguments.of(wrapped, fatal)));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void fatalIsPublishedBeforeCompletedStartHooksUnwindInOwnerOrder(boolean wrapped, Error fatal) {
        var owner = Thread.currentThread();
        var scopes = new ArrayDeque<String>();
        var calls = new ArrayList<String>();
        var endInfos = new ArrayList<UserFunctionEndInfo>();
        var reported = new AtomicReference<Error>();
        var cleanupAtPublication = new AtomicInteger(-1);
        var runner = new PluginRunner(
                List.of(
                        scope("first", scopes, calls, endInfos, owner, null),
                        scope("second", scopes, calls, endInfos, owner, null),
                        ignored -> new DurableExecutionPlugin() {
                            public void onUserFunctionStart(UserFunctionStartInfo info) {
                                if (wrapped) throw new CompletionException(new ExecutionException(fatal));
                                throw fatal;
                            }

                            public void onUserFunctionEnd(UserFunctionEndInfo info) {
                                calls.add("failed:end");
                            }
                        },
                        scope("unreached", scopes, calls, endInfos, owner, null)),
                error -> {
                    cleanupAtPublication.set(scopes.size());
                    reported.set(error);
                });
        runner.onInvocationStart(invocation());
        var start = start();
        assertSame(fatal, assertThrows(Error.class, () -> runner.onUserFunctionStart(start)));
        assertSame(fatal, reported.get());
        assertEquals(2, cleanupAtPublication.get(), "publication must wake the caller before cleanup can block");
        assertTrue(scopes.isEmpty(), "all earlier starts must still unwind on their owner before dispatch returns");
        assertEquals(List.of("first:start", "second:start", "second:end", "first:end"), calls);
        assertEquals(2, endInfos.size());
        for (var end : endInfos) {
            assertEquals(start.id(), end.id());
            assertEquals(start.attempt(), end.attempt());
            assertEquals(start.startTimestamp(), end.startTimestamp());
            assertEquals(UserFunctionOutcome.FAILED, end.outcome());
            assertSame(fatal, end.error());
        }
    }

    @Test
    void cleanupFatalDoesNotMaskOriginalOrSkipEarlierCleanup() {
        var fatal = new InternalError("start");
        var cleanupFailure = new InternalError("cleanup");
        var scopes = new ArrayDeque<String>();
        var calls = new ArrayList<String>();
        var ends = new ArrayList<UserFunctionEndInfo>();
        var reported = new AtomicReference<Error>();
        var runner = new PluginRunner(
                List.of(
                        scope("first", scopes, calls, ends, Thread.currentThread(), null),
                        scope("second", scopes, calls, ends, Thread.currentThread(), cleanupFailure),
                        ignored -> new DurableExecutionPlugin() {
                            public void onUserFunctionStart(UserFunctionStartInfo info) {
                                throw fatal;
                            }
                        }),
                reported::set);
        runner.onInvocationStart(invocation());
        assertSame(fatal, assertThrows(InternalError.class, () -> runner.onUserFunctionStart(start())));
        assertSame(fatal, reported.get());
        assertTrue(scopes.isEmpty());
        assertEquals(List.of("first:start", "second:start", "second:end", "first:end"), calls);
        assertArrayEquals(new Throwable[] {cleanupFailure}, fatal.getSuppressed());
    }

    private static DurableExecutionPluginFactory scope(
            String name,
            ArrayDeque<String> scopes,
            List<String> calls,
            List<UserFunctionEndInfo> ends,
            Thread owner,
            Error cleanupFailure) {
        return ignored -> new DurableExecutionPlugin() {
            public void onUserFunctionStart(UserFunctionStartInfo info) {
                scopes.push(name);
                calls.add(name + ":start");
            }

            public void onUserFunctionEnd(UserFunctionEndInfo info) {
                assertSame(owner, Thread.currentThread());
                assertEquals(name, scopes.pop());
                calls.add(name + ":end");
                ends.add(info);
                if (cleanupFailure != null) throw cleanupFailure;
            }
        };
    }

    private static InvocationInfo invocation() {
        return new InvocationInfo("request", "arn:test", true, Instant.EPOCH);
    }

    private static UserFunctionStartInfo start() {
        return new UserFunctionStartInfo("op", "step", "STEP", null, null, Instant.EPOCH, false, 1);
    }
}
