// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.RetryOperationConfig;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.retry.RetryStrategies;

class RetryOperationHelperTest {

    private DurableContext context;

    @BeforeEach
    void setUp() {
        context = mock(DurableContext.class);
    }

    // --- Named form tests ---

    @Nested
    class NamedForm {

        @Test
        void successOnFirstAttempt_wrapsInChildContext() {
            // runInChildContext should be called; delegate to the function immediately
            when(context.runInChildContext(eq("my-op"), any(TypeToken.class), any()))
                    .thenAnswer(invocation -> {
                        Function<DurableContext, ?> func = invocation.getArgument(2);
                        return func.apply(context);
                    });

            var config = RetryOperationConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            var result = RetryOperationHelper.retryOperation(context, "my-op", (ctx, attempt) -> "success", config);

            assertEquals("success", result);
            verify(context).runInChildContext(eq("my-op"), any(TypeToken.class), any());
        }

        @Test
        void successOnFirstAttempt_noChildContext_whenDisabled() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .wrapInChildContext(false)
                    .build();

            var result = RetryOperationHelper.retryOperation(context, "my-op", (ctx, attempt) -> "direct", config);

            assertEquals("direct", result);
            verify(context, never()).runInChildContext(anyString(), any(TypeToken.class), any());
        }

        @Test
        void retriesWithBackoffWaits_namedForm() {
            // Disable child context wrapping so we can directly verify wait calls
            var callCount = new int[] {0};
            var config = RetryOperationConfig.builder()
                    .retryStrategy((error, attempt) ->
                            attempt < 3 ? RetryDecision.retry(Duration.ofSeconds(5)) : RetryDecision.fail())
                    .wrapInChildContext(false)
                    .build();

            var result = RetryOperationHelper.retryOperation(
                    context,
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
            verify(context).wait("my-op-backoff-1", Duration.ofSeconds(5));
            verify(context).wait("my-op-backoff-2", Duration.ofSeconds(5));
            verify(context, times(2)).wait(anyString(), any(Duration.class));
        }

        @Test
        void rethrowsWhenRetryStrategyReturnsFail() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .wrapInChildContext(false)
                    .build();

            var exception = assertThrows(
                    RuntimeException.class,
                    () -> RetryOperationHelper.retryOperation(
                            context,
                            "my-op",
                            (ctx, attempt) -> {
                                throw new RuntimeException("terminal");
                            },
                            config));

            assertEquals("terminal", exception.getMessage());
            verify(context, never()).wait(anyString(), any(Duration.class));
        }

        @Test
        void usesDefaultDelayWhenRetryDecisionDelayIsZero() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy(
                            (error, attempt) -> attempt < 2 ? RetryDecision.retry(Duration.ZERO) : RetryDecision.fail())
                    .wrapInChildContext(false)
                    .build();

            var callCount = new int[] {0};
            var result = RetryOperationHelper.retryOperation(
                    context,
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
            verify(context).wait("my-op-backoff-1", Duration.ofSeconds(1));
        }

        @Test
        void nullContext_shouldThrow() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            assertThrows(
                    NullPointerException.class,
                    () -> RetryOperationHelper.retryOperation(null, "name", (ctx, a) -> "x", config));
        }

        @Test
        void nullName_shouldThrow() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            assertThrows(
                    NullPointerException.class,
                    () -> RetryOperationHelper.retryOperation(context, null, (ctx, a) -> "x", config));
        }

        @Test
        void nullOperation_shouldThrow() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            assertThrows(
                    NullPointerException.class,
                    () -> RetryOperationHelper.retryOperation(context, "name", null, config));
        }

        @Test
        void nullConfig_shouldThrow() {
            assertThrows(
                    NullPointerException.class,
                    () -> RetryOperationHelper.retryOperation(context, "name", (ctx, a) -> "x", null));
        }
    }

    // --- Anonymous form tests ---

    @Nested
    class AnonymousForm {

        @Test
        void successOnFirstAttempt() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            var result = RetryOperationHelper.retryOperation(context, (ctx, attempt) -> "anonymous-success", config);

            assertEquals("anonymous-success", result);
            verify(context, never()).runInChildContext(anyString(), any(TypeToken.class), any());
        }

        @Test
        void retriesWithAnonymousBackoffNames() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy((error, attempt) ->
                            attempt < 3 ? RetryDecision.retry(Duration.ofSeconds(2)) : RetryDecision.fail())
                    .build();

            var result = RetryOperationHelper.retryOperation(
                    context,
                    (ctx, attempt) -> {
                        if (attempt < 3) {
                            throw new RuntimeException("fail");
                        }
                        return "done";
                    },
                    config);

            assertEquals("done", result);
            verify(context).wait("retry-backoff-1", Duration.ofSeconds(2));
            verify(context).wait("retry-backoff-2", Duration.ofSeconds(2));
        }

        @Test
        void neverWrapsInChildContext_evenWhenConfigSaysTrue() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .wrapInChildContext(true) // should be ignored for anonymous form
                    .build();

            RetryOperationHelper.retryOperation(context, (ctx, attempt) -> "result", config);

            verify(context, never()).runInChildContext(anyString(), any(TypeToken.class), any());
        }

        @Test
        void rethrowsOriginalException() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            var original = new IllegalStateException("original error");
            var thrown = assertThrows(
                    IllegalStateException.class,
                    () -> RetryOperationHelper.retryOperation(
                            context,
                            (ctx, attempt) -> {
                                throw original;
                            },
                            config));

            assertSame(original, thrown);
        }

        @Test
        void nullContext_shouldThrow() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            assertThrows(
                    NullPointerException.class,
                    () -> RetryOperationHelper.retryOperation(null, (ctx, a) -> "x", config));
        }

        @Test
        void nullOperation_shouldThrow() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            assertThrows(
                    NullPointerException.class,
                    () -> RetryOperationHelper.retryOperation(context, (WithRetry<String>) null, config));
        }

        @Test
        void nullConfig_shouldThrow() {
            assertThrows(
                    NullPointerException.class,
                    () -> RetryOperationHelper.retryOperation(context, (ctx, a) -> "x", null));
        }
    }

    // --- Retry behavior tests ---

    @Nested
    class RetryBehavior {

        @Test
        void passesCorrectAttemptNumberToOperation() {
            var attempts = new ArrayList<Integer>();
            var config = RetryOperationConfig.builder()
                    .retryStrategy((error, attempt) ->
                            attempt < 4 ? RetryDecision.retry(Duration.ofSeconds(1)) : RetryDecision.fail())
                    .wrapInChildContext(false)
                    .build();

            RetryOperationHelper.retryOperation(
                    context,
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
            var config = RetryOperationConfig.builder()
                    .retryStrategy((error, attempt) -> {
                        errors.add(error);
                        return attempt < 3 ? RetryDecision.retry(Duration.ofSeconds(1)) : RetryDecision.fail();
                    })
                    .build();

            assertThrows(
                    RuntimeException.class,
                    () -> RetryOperationHelper.retryOperation(
                            context,
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
            var config = RetryOperationConfig.builder()
                    .retryStrategy((error, attempt) -> RetryDecision.retry(Duration.ofSeconds(attempt * 10L)))
                    .build();

            RetryOperationHelper.retryOperation(
                    context,
                    (ctx, attempt) -> {
                        if (attempt <= 2) {
                            throw new RuntimeException("fail");
                        }
                        return "ok";
                    },
                    config);

            verify(context).wait("retry-backoff-1", Duration.ofSeconds(10));
            verify(context).wait("retry-backoff-2", Duration.ofSeconds(20));
        }

        @Test
        void passesContextToOperation() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .build();

            RetryOperationHelper.retryOperation(
                    context,
                    (ctx, attempt) -> {
                        assertSame(context, ctx);
                        return "verified";
                    },
                    config);
        }

        @Test
        void namedFormPassesChildContextToOperation_whenWrapped() {
            var childContext = mock(DurableContext.class);
            when(context.runInChildContext(eq("wrapped"), any(TypeToken.class), any()))
                    .thenAnswer(invocation -> {
                        Function<DurableContext, ?> func = invocation.getArgument(2);
                        return func.apply(childContext);
                    });

            var config = RetryOperationConfig.builder()
                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                    .wrapInChildContext(true)
                    .build();

            RetryOperationHelper.retryOperation(
                    context,
                    "wrapped",
                    (ctx, attempt) -> {
                        assertSame(childContext, ctx);
                        return "verified";
                    },
                    config);
        }

        @Test
        void rethrowsLastExceptionWhenAllRetriesExhausted() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy(RetryStrategies.fixedDelay(3, Duration.ofSeconds(1)))
                    .build();

            var thrown = assertThrows(
                    RuntimeException.class,
                    () -> RetryOperationHelper.retryOperation(
                            context,
                            (ctx, attempt) -> {
                                throw new RuntimeException("attempt-" + attempt);
                            },
                            config));

            // The last attempt's exception is rethrown
            assertEquals("attempt-3", thrown.getMessage());
        }

        @Test
        void propagatesSuspendExecutionExceptionWithoutRetrying() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy((error, attempt) -> RetryDecision.retry(Duration.ofSeconds(1)))
                    .build();

            assertThrows(
                    SuspendExecutionException.class,
                    () -> RetryOperationHelper.retryOperation(
                            context,
                            (ctx, attempt) -> {
                                throw new SuspendExecutionException();
                            },
                            config));

            // Should never reach the wait — SuspendExecutionException propagates immediately
            verify(context, never()).wait(anyString(), any(Duration.class));
        }

        @Test
        void propagatesUnrecoverableDurableExecutionExceptionWithoutRetrying() {
            var config = RetryOperationConfig.builder()
                    .retryStrategy((error, attempt) -> RetryDecision.retry(Duration.ofSeconds(1)))
                    .build();

            assertThrows(
                    UnrecoverableDurableExecutionException.class,
                    () -> RetryOperationHelper.retryOperation(
                            context,
                            (ctx, attempt) -> {
                                throw new UnrecoverableDurableExecutionException(
                                        software.amazon.awssdk.services.lambda.model.ErrorObject.builder()
                                                .errorMessage("unrecoverable")
                                                .build());
                            },
                            config));

            verify(context, never()).wait(anyString(), any(Duration.class));
        }
    }
}
