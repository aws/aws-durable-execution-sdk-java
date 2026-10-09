// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

class InvocationEndFinalizationTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void waitsForHandlerFinallyAndRealOtelFlushBeforeResponding(boolean executionView, boolean retry) throws Exception {
        var flush = new BlockingFlush();
        var builder = SdkTracerProvider.builder().addSpanProcessor(flush);
        var settings = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(() -> new ExtractedContext(
                        "12345678901234567890123456789012", "1234567890123456", ExtractedContext.Sampling.SAMPLED))
                .build();
        DurableExecutionPlugin delegate = executionView
                ? new ExecutionOtelPlugin(builder, settings)
                : new InvocationOtelPlugin(builder, settings);
        var owner = new AtomicReference<Thread>();
        var endOwner = new AtomicReference<Thread>();
        var ends = new AtomicInteger();
        var plugin = new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                owner.set(Thread.currentThread());
                delegate.onInvocationStart(info);
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                endOwner.set(Thread.currentThread());
                delegate.onInvocationEnd(info);
                assertFalse(
                        Span.current().getSpanContext().isValid(), "End must restore ambient context before returning");
                ends.incrementAndGet();
            }
        };
        var workers = Executors.newCachedThreadPool();
        var callers = Executors.newSingleThreadExecutor();
        var enteredFinally = new CountDownLatch(1);
        var releaseFinally = new CountDownLatch(1);
        var original = new UnrecoverableDurableExecutionException(
                ErrorObject.builder().errorMessage("original retry").build(), true);
        var config = DurableConfig.builder()
                .withDurableExecutionClient(new LocalMemoryExecutionClient())
                .withExecutorService(workers)
                .withCheckpointDelay(Duration.ZERO)
                .withPlugins(plugin)
                .build();
        try {
            var response = callers.submit(() -> DurableExecutor.execute(
                    input(),
                    null,
                    TypeToken.get(String.class),
                    (value, ctx) -> {
                        assertSame(owner.get(), Thread.currentThread());
                        assertTrue(Span.current().getSpanContext().isValid());
                        try {
                            if (retry)
                                ctx.step("retry", String.class, step -> {
                                    throw original;
                                });
                            else ctx.wait("pause", Duration.ofSeconds(1));
                            return "done";
                        } finally {
                            enteredFinally.countDown();
                            await(releaseFinally);
                        }
                    },
                    config));
            assertTrue(enteredFinally.await(3, TimeUnit.SECONDS));
            assertThrows(
                    TimeoutException.class,
                    () -> response.get(600, TimeUnit.MILLISECONDS),
                    "suspension or termination cannot bypass a blocked handler finally");
            assertEquals(0, flush.calls.get(), "End must wait for the handler to unwind");
            assertEquals(0, ends.get());
            releaseFinally.countDown();
            assertTrue(flush.entered.await(3, TimeUnit.SECONDS));
            assertSame(owner.get(), endOwner.get());
            assertSame(owner.get(), flush.owner.get());
            assertThrows(
                    TimeoutException.class,
                    () -> response.get(100, TimeUnit.MILLISECONDS),
                    "the invocation response must wait for the plugin's actual forceFlush result");
            flush.result.succeed();
            if (retry) {
                var thrown = assertThrows(ExecutionException.class, () -> response.get(3, TimeUnit.SECONDS));
                assertSame(original, thrown.getCause());
            } else {
                assertEquals(
                        ExecutionStatus.PENDING,
                        response.get(3, TimeUnit.SECONDS).status());
            }
            assertEquals(1, flush.calls.get());
            assertEquals(1, ends.get());
        } finally {
            releaseFinally.countDown();
            flush.result.succeed();
            workers.shutdown();
            callers.shutdown();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static DurableExecutionInput input() {
        var operation = Operation.builder()
                .id("id")
                .name("test")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.now())
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/name/id",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operation).build());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static final class BlockingFlush implements SpanProcessor {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Thread> owner = new AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CompletableResultCode result = new CompletableResultCode();

        @Override
        public CompletableResultCode forceFlush() {
            calls.incrementAndGet();
            owner.set(Thread.currentThread());
            entered.countDown();
            return result;
        }

        @Override
        public void onStart(Context parent, ReadWriteSpan span) {}

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {}

        @Override
        public boolean isEndRequired() {
            return false;
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
