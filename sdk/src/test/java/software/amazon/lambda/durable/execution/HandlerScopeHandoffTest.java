// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;

class HandlerScopeHandoffTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void throwingCleanupCannotReplaceTheWinningControlFlow(boolean retry) throws Exception {
        var original = control(retry);
        var execution = CompletableFuture.<String>failedFuture(original);
        var handler = CompletableFuture.<String>failedFuture(new IllegalStateException("finally failed"));
        var result = handoff(execution, handler);
        var thrown = assertThrows(CompletionException.class, result::join);
        assertSame(original, thrown.getCause());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonUnwindingCleanupDoesNotWaitForever(boolean retry) throws Exception {
        var original = control(retry);
        var execution = CompletableFuture.<String>failedFuture(original);
        var handler = new CompletableFuture<String>();
        var caller = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "handoff-caller");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var response = caller.submit(() -> {
                var result = handoff(execution, handler);
                var thrown = assertThrows(CompletionException.class, result::join);
                assertSame(original, thrown.getCause());
                return null;
            });
            response.get(2, TimeUnit.SECONDS);
            assertFalse(handler.isDone(), "timeout must not complete or cancel the owner task");
        } finally {
            handler.complete("released");
            caller.shutdownNow();
        }
    }

    private static CompletableFuture<String> handoff(
            CompletableFuture<String> execution, CompletableFuture<String> handler) {
        return DurableExecutor.awaitHandlerScopes(execution, handler, new AtomicBoolean(true), null);
    }

    private static Throwable control(boolean retry) {
        return retry
                ? new UnrecoverableDurableExecutionException(
                        ErrorObject.builder().errorMessage("retry").build(), true)
                : new SuspendExecutionException();
    }
}
