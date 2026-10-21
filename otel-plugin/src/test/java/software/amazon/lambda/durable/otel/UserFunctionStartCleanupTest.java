// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

@Timeout(10)
class UserFunctionStartCleanupTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialStartFailureRestoresContextOnReusableDirectThread(boolean executionView) throws Exception {
        var thread = Executors.newSingleThreadExecutor(task -> daemon(task, "reused-direct-probe"));
        var fatal = new InternalError("later start hook");
        var starts = new AtomicInteger();
        var ends = new AtomicInteger();
        var settings = OtelPluginConfig.builder()
                .enableMdc(false)
                .contextExtractor(() -> new ExtractedContext(
                        "12345678901234567890123456789012", "1234567890123456", ExtractedContext.Sampling.SAMPLED))
                .build();
        var otel = executionView
                ? ExecutionOtelPlugin.factory(SdkTracerProvider.builder(), settings)
                : InvocationOtelPlugin.factory(SdkTracerProvider.builder(), settings);
        DurableExecutionPluginFactory observer = info -> new DurableExecutionPlugin() {
            public void onUserFunctionStart(UserFunctionStartInfo start) {
                starts.incrementAndGet();
            }

            public void onUserFunctionEnd(UserFunctionEndInfo end) {
                ends.incrementAndGet();
            }
        };
        DurableExecutionPluginFactory faulty = info -> new DurableExecutionPlugin() {
            public void onUserFunctionStart(UserFunctionStartInfo start) {
                throw fatal;
            }
        };
        try {
            thread.submit(() -> {
                        assertFalse(Span.current().getSpanContext().isValid());
                        assertSame(
                                fatal,
                                assertThrows(
                                        InternalError.class,
                                        () -> DurableExecutor.execute(
                                                input(),
                                                null,
                                                TypeToken.get(String.class),
                                                (value, ctx) -> {
                                                    ctx.stepAsync("trigger", String.class, step -> "unreachable");
                                                    return "done";
                                                },
                                                config(
                                                        new LocalMemoryExecutionClient(),
                                                        new DirectExecutor(),
                                                        otel,
                                                        observer,
                                                        faulty))));
                    })
                    .get(5, TimeUnit.SECONDS);
            assertEquals(1, starts.get());
            assertEquals(1, ends.get());
            var reusedSpan =
                    thread.submit(() -> Span.current().getSpanContext()).get(2, TimeUnit.SECONDS);
            assertFalse(reusedSpan.isValid(), "attempt cleanup must not restore an ended handler span");
        } finally {
            thread.shutdownNow();
            thread.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static DurableConfig config(
            LocalMemoryExecutionClient client, ExecutorService workers, DurableExecutionPluginFactory... plugins) {
        return DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withExecutorService(workers)
                .withCheckpointDelay(Duration.ZERO)
                .withPlugins(plugins)
                .build();
    }

    private static DurableExecutionInput input() {
        var operation = Operation.builder()
                .id("id")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/id",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operation).build());
    }

    private static Thread daemon(Runnable task, String name) {
        var thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((owner, error) -> {});
        return thread;
    }

    private static final class DirectExecutor extends AbstractExecutorService {
        public void execute(Runnable task) {
            task.run();
        }

        public void shutdown() {}

        public List<Runnable> shutdownNow() {
            return List.of();
        }

        public boolean isShutdown() {
            return false;
        }

        public boolean isTerminated() {
            return false;
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
