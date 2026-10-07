// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
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

    @Test
    void reservesFinalizationForEveryConfiguredPluginWithoutCancellingTheOwner() {
        var context = mock(Context.class);
        when(context.getRemainingTimeInMillis()).thenReturn(10_000);
        var execution = CompletableFuture.<String>failedFuture(control(false));
        var handler = new CompletableFuture<String>();
        assertTimeoutPreemptively(
                Duration.ofMillis(200),
                () -> assertSame(
                        execution,
                        DurableExecutor.awaitHandlerScopes(
                                execution, handler, new AtomicBoolean(true), context, 2, new AtomicReference<>())));
        assertFalse(handler.isDone());
    }

    @Test
    void noScopeDoesNotConsultTheRemainingTimeOrWaitForTheHandler() {
        var context = mock(Context.class);
        var execution = CompletableFuture.<String>failedFuture(control(false));
        var handler = new CompletableFuture<String>();
        assertSame(
                execution,
                DurableExecutor.awaitHandlerScopes(
                        execution, handler, new AtomicBoolean(false), context, 1, new AtomicReference<>()));
        verifyNoInteractions(context);
        assertFalse(handler.isDone());
    }

    @Test
    void knownFatalStillGivesEarlierScopeCleanupItsHandoffBudget() throws Exception {
        var fatal = new InternalError("later scope failed");
        var handler = new CompletableFuture<String>();
        var callers = Executors.newSingleThreadExecutor();
        try {
            var result = callers.submit(() -> DurableExecutor.awaitHandlerScopes(
                    CompletableFuture.<String>failedFuture(fatal),
                    handler,
                    new AtomicBoolean(true),
                    null,
                    2,
                    new AtomicReference<Error>(fatal)));
            assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
            handler.completeExceptionally(fatal);
            var chosen = result.get(1, TimeUnit.SECONDS);
            assertSame(
                    fatal, assertThrows(CompletionException.class, chosen::join).getCause());
        } finally {
            handler.completeExceptionally(fatal);
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void completedHandoffWaitsForTheRegisteredOwnerFinalizer() throws Exception {
        var callbackRegistered = new CountDownLatch(1);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var handler = new CompletableFuture<String>() {
            @Override
            public CompletableFuture<String> whenComplete(BiConsumer<? super String, ? super Throwable> action) {
                callbackRegistered.countDown();
                // A completed future may publish its result before its registered completion action executes.
                return super.whenCompleteAsync(action, callbacks::add);
            }
        };
        var local = new ThreadLocal<String>();
        var owner = new AtomicReference<Thread>();
        var finalizerThread = new AtomicReference<Thread>();
        var finalizerValue = new AtomicReference<String>();
        var workers = Executors.newSingleThreadExecutor();
        var callers = Executors.newSingleThreadExecutor();
        var method = DurableExecutor.class.getDeclaredMethod(
                "finalizeAfterHandlerScopes",
                CompletableFuture.class,
                CompletableFuture.class,
                AtomicBoolean.class,
                Context.class,
                int.class,
                AtomicReference.class,
                BiFunction.class);
        method.setAccessible(true);
        BiFunction<String, Throwable, String> end = (value, failure) -> {
            finalizerThread.set(Thread.currentThread());
            finalizerValue.set(local.get());
            local.remove();
            return "pending";
        };
        try {
            var result = callers.submit(() -> method.invoke(
                    null,
                    CompletableFuture.<String>failedFuture(control(false)),
                    handler,
                    new AtomicBoolean(true),
                    null,
                    2,
                    new AtomicReference<Error>(),
                    end));
            assertTrue(callbackRegistered.await(3, TimeUnit.SECONDS));
            workers.submit(() -> {
                        owner.set(Thread.currentThread());
                        local.set("invocation");
                        handler.complete("handler finished");
                    })
                    .get(3, TimeUnit.SECONDS);
            assertTrue(handler.isDone());
            assertThrows(
                    TimeoutException.class,
                    () -> result.get(100, TimeUnit.MILLISECONDS),
                    "The waiter must not steal a completed handoff's pending completion callback");
            var callback = callbacks.poll(3, TimeUnit.SECONDS);
            assertNotNull(callback);
            workers.submit(callback).get(3, TimeUnit.SECONDS);
            assertEquals("pending", result.get(3, TimeUnit.SECONDS));
            assertSame(owner.get(), finalizerThread.get());
            assertEquals("invocation", finalizerValue.get());
            assertNull(workers.submit(local::get).get(3, TimeUnit.SECONDS), "The reused owner must be clean");
        } finally {
            handler.complete("cleanup");
            Runnable callback;
            while ((callback = callbacks.poll()) != null)
                workers.submit(callback).get(3, TimeUnit.SECONDS);
            callers.shutdownNow();
            workers.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static CompletableFuture<String> handoff(
            CompletableFuture<String> execution, CompletableFuture<String> handler) {
        return DurableExecutor.awaitHandlerScopes(
                execution, handler, new AtomicBoolean(true), null, 1, new AtomicReference<>());
    }

    private static Throwable control(boolean retry) {
        return retry
                ? new UnrecoverableDurableExecutionException(
                        ErrorObject.builder().errorMessage("retry").build(), true)
                : new SuspendExecutionException();
    }
}
