// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.WithRetryConfig;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.model.WithRetry;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.retry.RetryStrategies;

@SuppressWarnings("unchecked")
class DurableContextWithRetryTest {

    private DurableContext context;
    private DurableContext childContext;

    @BeforeEach
    void setUp() {
        context = mock(DurableContext.class);
        childContext = mock(DurableContext.class);
        stubWithRetryMethods(context);
        stubWithRetryMethods(childContext);
    }

    /**
     * Stubs the withRetry/withRetryAsync methods on a mock DurableContext so they execute the real retry loop logic.
     * This is needed because withRetry/withRetryAsync are abstract interface methods, and mocks return null by default.
     *
     * <p>All forms always run in a child context (matching the real implementation which uses a virtual child context
     * when {@code wrapInChildContext} is false, and a checkpointed child context when true).
     */
    private void stubWithRetryMethods(DurableContext mock) {
        // Named sync form — always runs in a child context
        when(mock.<Object>withRetry(any(), nullable(WithRetry.class), nullable(WithRetryConfig.class)))
                .thenAnswer(invocation -> {
                    String name = invocation.getArgument(0);
                    WithRetry<Object> operation = invocation.getArgument(1);
                    WithRetryConfig config = invocation.getArgument(2);
                    Objects.requireNonNull(name, "name cannot be null");
                    Objects.requireNonNull(operation, "operation cannot be null");
                    Objects.requireNonNull(config, "config cannot be null");
                    return mock.runInChildContextAsync(
                                    name,
                                    new TypeToken<Object>() {},
                                    childCtx -> executeRetryLoop(childCtx, name, operation, config))
                            .get();
                });

        // Anonymous sync form — always runs in a child context
        when(mock.<Object>withRetry(nullable(WithRetry.class), nullable(WithRetryConfig.class)))
                .thenAnswer(invocation -> {
                    WithRetry<Object> operation = invocation.getArgument(0);
                    WithRetryConfig config = invocation.getArgument(1);
                    Objects.requireNonNull(operation, "operation cannot be null");
                    Objects.requireNonNull(config, "config cannot be null");
                    return mock.runInChildContextAsync(
                                    "retry",
                                    new TypeToken<Object>() {},
                                    childCtx -> executeRetryLoop(childCtx, null, operation, config))
                            .get();
                });

        // Named async form
        when(mock.<Object>withRetryAsync(any(), nullable(WithRetry.class), nullable(WithRetryConfig.class)))
                .thenAnswer(invocation -> {
                    String name = invocation.getArgument(0);
                    WithRetry<Object> operation = invocation.getArgument(1);
                    WithRetryConfig config = invocation.getArgument(2);
                    Objects.requireNonNull(name, "name cannot be null");
                    Objects.requireNonNull(operation, "operation cannot be null");
                    Objects.requireNonNull(config, "config cannot be null");
                    return mock.runInChildContextAsync(
                            name,
                            new TypeToken<Object>() {},
                            childCtx -> executeRetryLoop(childCtx, name, operation, config));
                });

        // Anonymous async form
        when(mock.<Object>withRetryAsync(nullable(WithRetry.class), nullable(WithRetryConfig.class)))
                .thenAnswer(invocation -> {
                    WithRetry<Object> operation = invocation.getArgument(0);
                    WithRetryConfig config = invocation.getArgument(1);
                    Objects.requireNonNull(operation, "operation cannot be null");
                    Objects.requireNonNull(config, "config cannot be null");
                    return mock.runInChildContextAsync(
                            "retry",
                            new TypeToken<Object>() {},
                            childCtx -> executeRetryLoop(childCtx, null, operation, config));
                });
    }

    /** Replicates the retry loop logic from DurableContextImpl for test stubbing. */
    private static <T> T executeRetryLoop(
            DurableContext context, String name, WithRetry<T> operation, WithRetryConfig config) {
        var attempt = 1;
        while (true) {
            try {
                return operation.execute(context, attempt);
            } catch (SuspendExecutionException | UnrecoverableDurableExecutionException e) {
                throw e;
            } catch (Exception e) {
                var decision = config.retryStrategy().makeRetryDecision(e, attempt);
                if (!decision.shouldRetry()) {
                    throw e;
                }
                var delay = decision.delay().isZero() ? Duration.ofSeconds(1) : decision.delay();
                var waitName = name != null ? name + "-backoff-" + attempt : "retry-backoff-" + attempt;
                context.wait(waitName, delay);
                attempt++;
            }
        }
    }

    /** Stubs runInChildContextAsync to immediately execute the function with childContext. */
    private void stubChildContext(String name) {
        when(context.runInChildContextAsync(eq(name), any(TypeToken.class), any()))
                .thenAnswer(invocation -> {
                    Function<DurableContext, ?> func = invocation.getArgument(2);
                    var result = func.apply(childContext);
                    return (DurableFuture<Object>) () -> result;
                });
    }

    /** Stubs runInChildContextAsync for any name to immediately execute the function with childContext. */
    private void stubChildContextAnyName() {
        when(context.runInChildContextAsync(anyString(), any(TypeToken.class), any()))
                .thenAnswer(invocation -> {
                    Function<DurableContext, ?> func = invocation.getArgument(2);
                    var result = func.apply(childContext);
                    return (DurableFuture<Object>) () -> result;
                });
    }

    // --- Named form tests ---

    @Nested
    class NamedForm {

        @BeforeEach
        void setUpChildContext() {
            stubChildContext("my-op");
            stubChildContext("name");
        }

        @Test
        void successOnFirstAttempt() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            var result = context.withRetry("my-op", (ctx, attempt) -> "success", config);

            assertEquals("success", result);
            verify(context).runInChildContextAsync(eq("my-op"), any(TypeToken.class), any());
        }

        @Test
        void alwaysUsesChildContext() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            context.withRetry("my-op", (ctx, attempt) -> "result", config);

            verify(context).runInChildContextAsync(eq("my-op"), any(TypeToken.class), any());
        }

        @Test
        void retriesWithBackoffWaits_namedForm() {
            var callCount = new int[] {0};
            var config = WithRetryConfig.builder()
                    .retryStrategy((error, attempt) ->
                            attempt < 3 ? RetryDecision.retry(Duration.ofSeconds(5)) : RetryDecision.fail())
                    .build();

            var result = context.withRetry(
                    "my-op",
                    (ctx, attempt) -> {
                        callCount[0]++;
                        if (attempt < 3) {
                            throw new RuntimeException("fail-" + attempt);
                        }
                        return "success-on-3";
                    },
                    config);

            assertEquals("success-on-3", result);
            assertEquals(3, callCount[0]);
            // Backoff waits happen on the child context
            verify(childContext).wait("my-op-backoff-1", Duration.ofSeconds(5));
            verify(childContext).wait("my-op-backoff-2", Duration.ofSeconds(5));
            verify(childContext, times(2)).wait(anyString(), any(Duration.class));
        }

        @Test
        void rethrowsWhenRetryStrategyReturnsFail() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            var exception = assertThrows(
                    RuntimeException.class,
                    () -> context.withRetry(
                            "my-op",
                            (ctx, attempt) -> {
                                throw new RuntimeException("terminal");
                            },
                            config));

            assertEquals("terminal", exception.getMessage());
            verify(childContext, never()).wait(anyString(), any(Duration.class));
        }

        @Test
        void usesDefaultDelayWhenRetryDecisionDelayIsZero() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(
                            (error, attempt) -> attempt < 2 ? RetryDecision.retry(Duration.ZERO) : RetryDecision.fail())
                    .build();

            var callCount = new int[] {0};
            var result = context.withRetry(
                    "my-op",
                    (ctx, attempt) -> {
                        callCount[0]++;
                        if (attempt == 1) {
                            throw new RuntimeException("fail");
                        }
                        return "ok";
                    },
                    config);

            assertEquals("ok", result);
            // Zero delay should be replaced with 1-second default
            verify(childContext).wait("my-op-backoff-1", Duration.ofSeconds(1));
        }

        @Test
        void nullName_shouldThrow() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            assertThrows(NullPointerException.class, () -> context.withRetry(null, (ctx, a) -> "x", config));
        }

        @Test
        void nullOperation_shouldThrow() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            assertThrows(NullPointerException.class, () -> context.withRetry("name", null, config));
        }

        @Test
        void nullConfig_shouldThrow() {
            assertThrows(NullPointerException.class, () -> context.withRetry("name", (ctx, a) -> "x", null));
        }

        @Test
        void operationReturnsNull() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            var result = context.withRetry("my-op", (WithRetry<String>) (ctx, attempt) -> null, config);

            assertNull(result);
        }

        @Test
        void passesChildContextToOperation() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            context.withRetry(
                    "my-op",
                    (ctx, attempt) -> {
                        assertSame(childContext, ctx);
                        return "verified";
                    },
                    config);
        }
    }

    // --- Anonymous form tests ---

    @Nested
    class AnonymousForm {

        @BeforeEach
        void setUpChildContext() {
            stubChildContext("retry");
        }

        @Test
        void successOnFirstAttempt() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            var result = context.withRetry((ctx, attempt) -> "anonymous-success", config);

            assertEquals("anonymous-success", result);
        }

        @Test
        void alwaysUsesChildContext() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            context.withRetry((ctx, attempt) -> "result", config);

            verify(context).runInChildContextAsync(eq("retry"), any(TypeToken.class), any());
        }

        @Test
        void retriesWithAnonymousBackoffNames() {
            var config = WithRetryConfig.builder()
                    .retryStrategy((error, attempt) ->
                            attempt < 3 ? RetryDecision.retry(Duration.ofSeconds(2)) : RetryDecision.fail())
                    .build();

            var result = context.withRetry(
                    (ctx, attempt) -> {
                        if (attempt < 3) {
                            throw new RuntimeException("fail");
                        }
                        return "done";
                    },
                    config);

            assertEquals("done", result);
            verify(childContext).wait("retry-backoff-1", Duration.ofSeconds(2));
            verify(childContext).wait("retry-backoff-2", Duration.ofSeconds(2));
        }

        @Test
        void rethrowsOriginalException() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            var original = new IllegalStateException("original error");
            var thrown = assertThrows(
                    IllegalStateException.class,
                    () -> context.withRetry(
                            (ctx, attempt) -> {
                                throw original;
                            },
                            config));

            assertSame(original, thrown);
        }

        @Test
        void nullOperation_shouldThrow() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            assertThrows(NullPointerException.class, () -> context.withRetry((WithRetry<String>) null, config));
        }

        @Test
        void nullConfig_shouldThrow() {
            assertThrows(NullPointerException.class, () -> context.withRetry((ctx, a) -> "x", null));
        }

        @Test
        void operationReturnsNull() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            var result = context.withRetry((WithRetry<String>) (ctx, attempt) -> null, config);

            assertNull(result);
        }

        @Test
        void usesDefaultDelayWhenRetryDecisionDelayIsZero() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(
                            (error, attempt) -> attempt < 2 ? RetryDecision.retry(Duration.ZERO) : RetryDecision.fail())
                    .build();

            var result = context.withRetry(
                    (ctx, attempt) -> {
                        if (attempt == 1) {
                            throw new RuntimeException("fail");
                        }
                        return "ok";
                    },
                    config);

            assertEquals("ok", result);
            verify(childContext).wait("retry-backoff-1", Duration.ofSeconds(1));
        }

        @Test
        void passesChildContextToOperation() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            context.withRetry(
                    (ctx, attempt) -> {
                        assertSame(childContext, ctx);
                        return "verified";
                    },
                    config);
        }
    }

    // --- Async form tests ---

    @Nested
    class AsyncForm {

        @Test
        void namedAsyncReturnsDurableFuture() {
            stubChildContext("my-op");

            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            DurableFuture<String> future = context.withRetryAsync("my-op", (ctx, attempt) -> "async-result", config);

            assertNotNull(future);
            assertEquals("async-result", future.get());
        }

        @Test
        void namedAsyncAlwaysWrapsInChildContext() {
            stubChildContext("my-op");

            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            context.withRetryAsync("my-op", (ctx, attempt) -> "result", config);

            verify(context).runInChildContextAsync(eq("my-op"), any(TypeToken.class), any());
        }

        @Test
        void anonymousAsyncReturnsDurableFuture() {
            stubChildContext("retry");

            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            DurableFuture<String> future = context.withRetryAsync((ctx, attempt) -> "anon-async", config);

            assertNotNull(future);
            assertEquals("anon-async", future.get());
        }

        @Test
        void anonymousAsyncWrapsInChildContext() {
            stubChildContext("retry");

            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            context.withRetryAsync((ctx, attempt) -> "result", config);

            verify(context).runInChildContextAsync(eq("retry"), any(TypeToken.class), any());
        }

        @Test
        void namedAsyncRetriesWithBackoff() {
            stubChildContext("my-op");

            var config = WithRetryConfig.builder()
                    .retryStrategy((error, attempt) ->
                            attempt < 3 ? RetryDecision.retry(Duration.ofSeconds(5)) : RetryDecision.fail())
                    .build();

            var callCount = new int[] {0};
            DurableFuture<String> future = context.withRetryAsync(
                    "my-op",
                    (ctx, attempt) -> {
                        callCount[0]++;
                        if (attempt < 3) {
                            throw new RuntimeException("fail-" + attempt);
                        }
                        return "success-on-3";
                    },
                    config);

            assertEquals("success-on-3", future.get());
            assertEquals(3, callCount[0]);
            verify(childContext).wait("my-op-backoff-1", Duration.ofSeconds(5));
            verify(childContext).wait("my-op-backoff-2", Duration.ofSeconds(5));
        }

        @Test
        void anonymousAsyncRetriesWithBackoff() {
            stubChildContext("retry");

            var config = WithRetryConfig.builder()
                    .retryStrategy((error, attempt) ->
                            attempt < 3 ? RetryDecision.retry(Duration.ofSeconds(2)) : RetryDecision.fail())
                    .build();

            DurableFuture<String> future = context.withRetryAsync(
                    (ctx, attempt) -> {
                        if (attempt < 3) {
                            throw new RuntimeException("fail");
                        }
                        return "done";
                    },
                    config);

            assertEquals("done", future.get());
            verify(childContext).wait("retry-backoff-1", Duration.ofSeconds(2));
            verify(childContext).wait("retry-backoff-2", Duration.ofSeconds(2));
        }

        @Test
        void namedAsyncNullName_shouldThrow() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            assertThrows(NullPointerException.class, () -> context.withRetryAsync(null, (ctx, a) -> "x", config));
        }

        @Test
        void namedAsyncNullOperation_shouldThrow() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            assertThrows(NullPointerException.class, () -> context.withRetryAsync("name", null, config));
        }

        @Test
        void namedAsyncNullConfig_shouldThrow() {
            assertThrows(NullPointerException.class, () -> context.withRetryAsync("name", (ctx, a) -> "x", null));
        }

        @Test
        void anonymousAsyncNullOperation_shouldThrow() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            assertThrows(NullPointerException.class, () -> context.withRetryAsync((WithRetry<String>) null, config));
        }

        @Test
        void anonymousAsyncNullConfig_shouldThrow() {
            assertThrows(NullPointerException.class, () -> context.withRetryAsync((ctx, a) -> "x", null));
        }

        @Test
        void asyncOperationReturnsNull() {
            stubChildContext("my-op");

            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            DurableFuture<String> future =
                    context.withRetryAsync("my-op", (WithRetry<String>) (ctx, attempt) -> null, config);

            assertNull(future.get());
        }

        @Test
        void syncAndAsyncProduceSameResult() {
            stubChildContextAnyName();

            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            var syncResult = context.withRetry("op", (ctx, attempt) -> "value", config);
            var asyncResult = context.withRetryAsync("op", (ctx, attempt) -> "value", config)
                    .get();

            assertEquals(syncResult, asyncResult);
        }
    }

    // --- Retry behavior tests ---

    @Nested
    class RetryBehavior {

        @BeforeEach
        void setUpChildContext() {
            stubChildContextAnyName();
        }

        @Test
        void passesCorrectAttemptNumberToOperation() {
            var attempts = new ArrayList<Integer>();
            var config = WithRetryConfig.builder()
                    .retryStrategy((error, attempt) ->
                            attempt < 4 ? RetryDecision.retry(Duration.ofSeconds(1)) : RetryDecision.fail())
                    .build();

            context.withRetry(
                    (ctx, attempt) -> {
                        attempts.add(attempt);
                        if (attempt < 4) {
                            throw new RuntimeException("not yet");
                        }
                        return "done";
                    },
                    config);

            assertEquals(4, attempts.size());
            assertEquals(1, attempts.get(0));
            assertEquals(2, attempts.get(1));
            assertEquals(3, attempts.get(2));
            assertEquals(4, attempts.get(3));
        }

        @Test
        void passesCorrectAttemptNumberToOperation_namedForm() {
            var attempts = new ArrayList<Integer>();
            var config = WithRetryConfig.builder()
                    .retryStrategy((error, attempt) ->
                            attempt < 4 ? RetryDecision.retry(Duration.ofSeconds(1)) : RetryDecision.fail())
                    .build();

            context.withRetry(
                    "track",
                    (ctx, attempt) -> {
                        attempts.add(attempt);
                        if (attempt < 4) {
                            throw new RuntimeException("not yet");
                        }
                        return "done";
                    },
                    config);

            assertEquals(4, attempts.size());
            assertEquals(1, attempts.get(0));
            assertEquals(2, attempts.get(1));
            assertEquals(3, attempts.get(2));
            assertEquals(4, attempts.get(3));
        }

        @Test
        void passesErrorToRetryStrategy() {
            var errors = new ArrayList<Throwable>();
            var config = WithRetryConfig.builder()
                    .retryStrategy((error, attempt) -> {
                        errors.add(error);
                        return attempt < 3 ? RetryDecision.retry(Duration.ofSeconds(1)) : RetryDecision.fail();
                    })
                    .build();

            assertThrows(
                    RuntimeException.class,
                    () -> context.withRetry(
                            (ctx, attempt) -> {
                                throw new RuntimeException("error-" + attempt);
                            },
                            config));

            assertEquals(3, errors.size());
            assertEquals("error-1", errors.get(0).getMessage());
            assertEquals("error-2", errors.get(1).getMessage());
            assertEquals("error-3", errors.get(2).getMessage());
        }

        @Test
        void respectsCustomDelayFromRetryDecision() {
            var config = WithRetryConfig.builder()
                    .retryStrategy((error, attempt) -> RetryDecision.retry(Duration.ofSeconds(attempt * 10L)))
                    .build();

            context.withRetry(
                    (ctx, attempt) -> {
                        if (attempt <= 2) {
                            throw new RuntimeException("fail");
                        }
                        return "ok";
                    },
                    config);

            verify(childContext).wait("retry-backoff-1", Duration.ofSeconds(10));
            verify(childContext).wait("retry-backoff-2", Duration.ofSeconds(20));
        }

        @Test
        void anonymousFormPassesChildContextToOperation() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            context.withRetry(
                    (ctx, attempt) -> {
                        assertSame(childContext, ctx);
                        return "verified";
                    },
                    config);
        }

        @Test
        void namedFormPassesChildContextToOperation() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            context.withRetry(
                    "wrapped",
                    (ctx, attempt) -> {
                        assertSame(childContext, ctx);
                        return "verified";
                    },
                    config);
        }

        @Test
        void rethrowsLastExceptionWhenAllRetriesExhausted() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.fixedDelay(3, Duration.ofSeconds(1)))
                    .build();

            var thrown = assertThrows(
                    RuntimeException.class,
                    () -> context.withRetry(
                            (ctx, attempt) -> {
                                throw new RuntimeException("attempt-" + attempt);
                            },
                            config));

            // The last attempt's exception is rethrown
            assertEquals("attempt-3", thrown.getMessage());
        }

        @Test
        void propagatesSuspendExecutionExceptionWithoutRetrying() {
            var config = WithRetryConfig.builder()
                    .retryStrategy((error, attempt) -> RetryDecision.retry(Duration.ofSeconds(1)))
                    .build();

            assertThrows(
                    SuspendExecutionException.class,
                    () -> context.withRetry(
                            (ctx, attempt) -> {
                                throw new SuspendExecutionException();
                            },
                            config));

            // Should never reach the wait — SuspendExecutionException propagates immediately
            verify(childContext, never()).wait(anyString(), any(Duration.class));
        }

        @Test
        void propagatesUnrecoverableDurableExecutionExceptionWithoutRetrying() {
            var config = WithRetryConfig.builder()
                    .retryStrategy((error, attempt) -> RetryDecision.retry(Duration.ofSeconds(1)))
                    .build();

            assertThrows(
                    UnrecoverableDurableExecutionException.class,
                    () -> context.withRetry(
                            (ctx, attempt) -> {
                                throw new UnrecoverableDurableExecutionException(
                                        software.amazon.awssdk.services.lambda.model.ErrorObject.builder()
                                                .errorMessage("unrecoverable")
                                                .build());
                            },
                            config));

            verify(childContext, never()).wait(anyString(), any(Duration.class));
        }

        @Test
        void propagatesSuspendExecutionExceptionOnLaterAttempt() {
            var config = WithRetryConfig.builder()
                    .retryStrategy((error, attempt) -> RetryDecision.retry(Duration.ofSeconds(1)))
                    .build();

            assertThrows(
                    SuspendExecutionException.class,
                    () -> context.withRetry(
                            (ctx, attempt) -> {
                                if (attempt == 1) {
                                    throw new RuntimeException("transient");
                                }
                                // Second attempt triggers suspend — must propagate, not retry
                                throw new SuspendExecutionException();
                            },
                            config));

            // First attempt retried (one backoff wait), second attempt suspended immediately
            verify(childContext, times(1)).wait(anyString(), any(Duration.class));
        }

        @Test
        void propagatesUnrecoverableDurableExecutionExceptionOnLaterAttempt() {
            var config = WithRetryConfig.builder()
                    .retryStrategy((error, attempt) -> RetryDecision.retry(Duration.ofSeconds(1)))
                    .build();

            assertThrows(
                    UnrecoverableDurableExecutionException.class,
                    () -> context.withRetry(
                            (ctx, attempt) -> {
                                if (attempt == 1) {
                                    throw new RuntimeException("transient");
                                }
                                throw new UnrecoverableDurableExecutionException(
                                        software.amazon.awssdk.services.lambda.model.ErrorObject.builder()
                                                .errorMessage("unrecoverable on attempt 2")
                                                .build());
                            },
                            config));

            verify(childContext, times(1)).wait(anyString(), any(Duration.class));
        }

        @Test
        void preservesCheckedExceptionSubclassType() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            var original = new SerDesException("deserialization failed", new RuntimeException("bad json"));

            var thrown = assertThrows(
                    SerDesException.class,
                    () -> context.withRetry(
                            (ctx, attempt) -> {
                                throw original;
                            },
                            config));

            assertSame(original, thrown);
            assertEquals("deserialization failed", thrown.getMessage());
        }
    }

    // --- WrapInChildContext tests ---

    @Nested
    class WrapInChildContext {

        @BeforeEach
        void setUpChildContext() {
            stubChildContextAnyName();
        }

        @Test
        void namedForm_alwaysUsesChildContextWhenEnabled() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .wrapInChildContext(true)
                    .build();

            var result = context.withRetry("my-op", (ctx, attempt) -> "wrapped", config);

            assertEquals("wrapped", result);
            verify(context).runInChildContextAsync(eq("my-op"), any(TypeToken.class), any());
        }

        @Test
        void namedForm_usesChildContextWhenDisabled() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .wrapInChildContext(false)
                    .build();

            var result = context.withRetry("my-op", (ctx, attempt) -> "virtual", config);

            assertEquals("virtual", result);
            // Still uses a child context (virtual in real impl)
            verify(context).runInChildContextAsync(eq("my-op"), any(TypeToken.class), any());
        }

        @Test
        void namedForm_usesChildContextByDefault() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            context.withRetry("my-op", (ctx, attempt) -> "virtual", config);

            verify(context).runInChildContextAsync(eq("my-op"), any(TypeToken.class), any());
        }

        @Test
        void anonymousForm_alwaysUsesChildContextWhenEnabled() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .wrapInChildContext(true)
                    .build();

            var result = context.withRetry((ctx, attempt) -> "wrapped", config);

            assertEquals("wrapped", result);
            verify(context).runInChildContextAsync(eq("retry"), any(TypeToken.class), any());
        }

        @Test
        void anonymousForm_usesChildContextWhenDisabled() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .wrapInChildContext(false)
                    .build();

            var result = context.withRetry((ctx, attempt) -> "virtual", config);

            assertEquals("virtual", result);
            // Still uses a child context (virtual in real impl)
            verify(context).runInChildContextAsync(eq("retry"), any(TypeToken.class), any());
        }

        @Test
        void namedForm_passesChildContextToOperationWhenWrapped() {
            var config = WithRetryConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .wrapInChildContext(true)
                    .build();

            context.withRetry(
                    "my-op",
                    (ctx, attempt) -> {
                        assertSame(childContext, ctx);
                        return "verified";
                    },
                    config);
        }

        @Test
        void namedForm_retriesWithBackoffOnChildContextWhenWrapped() {
            var config = WithRetryConfig.builder()
                    .retryStrategy((error, attempt) ->
                            attempt < 3 ? RetryDecision.retry(Duration.ofSeconds(5)) : RetryDecision.fail())
                    .wrapInChildContext(true)
                    .build();

            var result = context.withRetry(
                    "my-op",
                    (ctx, attempt) -> {
                        if (attempt < 3) {
                            throw new RuntimeException("fail-" + attempt);
                        }
                        return "success-on-3";
                    },
                    config);

            assertEquals("success-on-3", result);
            verify(childContext).wait("my-op-backoff-1", Duration.ofSeconds(5));
            verify(childContext).wait("my-op-backoff-2", Duration.ofSeconds(5));
        }

        @Test
        void anonymousForm_retriesWithBackoffOnChildContextWhenWrapped() {
            var config = WithRetryConfig.builder()
                    .retryStrategy((error, attempt) ->
                            attempt < 3 ? RetryDecision.retry(Duration.ofSeconds(2)) : RetryDecision.fail())
                    .wrapInChildContext(true)
                    .build();

            var result = context.withRetry(
                    (ctx, attempt) -> {
                        if (attempt < 3) {
                            throw new RuntimeException("fail");
                        }
                        return "done";
                    },
                    config);

            assertEquals("done", result);
            verify(childContext).wait("retry-backoff-1", Duration.ofSeconds(2));
            verify(childContext).wait("retry-backoff-2", Duration.ofSeconds(2));
        }
    }
}
