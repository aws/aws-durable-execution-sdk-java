// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.lmi;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.serde.DurableInputOutputSerDes;
import software.amazon.lambda.durable.serde.JacksonSerDes;

/** Fault fixtures: blocking models user code/cleanup, never a durable delay. */
public final class LifecycleHandler implements RequestStreamHandler {
    private static final DurableInputOutputSerDes WIRE = new DurableInputOutputSerDes();
    private static final JacksonSerDes JSON = new JacksonSerDes();
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final ConcurrentHashMap<String, CountDownLatch> BARRIERS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService ESCAPES = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "lmi-test-escape");
        thread.setDaemon(true);
        return thread;
    });
    private static final StepConfig NO_RETRY =
            StepConfig.builder().retryStrategy(RetryStrategies.Presets.NO_RETRY).build();
    private final DurableConfig shared = createSharedConfiguration();

    private static DurableConfig createSharedConfiguration() {
        if ("fixed".equals(System.getenv("LMI_EXECUTOR"))) {
            return DurableConfig.builder()
                    .withExecutorService(Executors.newFixedThreadPool(2))
                    .build();
        }
        if ("nested".equals(System.getenv("LMI_EXECUTOR"))) {
            // Leave capacity for both roots to start their child contexts. The two child handlers then occupy the
            // remaining workers while synchronously waiting for nested map/parallel work, independently exercising
            // nested orchestration starvation rather than failing at the roots' first ordinary steps.
            return DurableConfig.builder()
                    .withExecutorService(Executors.newFixedThreadPool(4))
                    .build();
        }
        return DurableConfig.defaultConfig();
    }

    @Override
    public void handleRequest(InputStream source, OutputStream destination, Context runtime) throws IOException {
        var input = WIRE.deserialize(
                new String(source.readAllBytes(), StandardCharsets.UTF_8), TypeToken.get(DurableExecutionInput.class));
        var trace = new InvocationTrace(readUserInput(input), runtime, input.durableExecutionArn());
        trace.wrapperEnter();
        var status = "THREW";
        try {
            var config = DurableConfig.builder()
                    .withExecutorService(shared.getExecutorService())
                    .withDurableExecutionClient(new ObservedClient(shared.getDurableExecutionClient(), trace))
                    .build();
            var response = DurableExecutor.execute(
                    input,
                    runtime,
                    TypeToken.get(FixtureInput.class),
                    (value, context) -> handle(value, context, trace),
                    config);
            destination.write(WIRE.serialize(response).getBytes(StandardCharsets.UTF_8));
            status = response.status().name();
        } finally {
            trace.snapshot((ThreadPoolExecutor) shared.getExecutorService());
            trace.wrapperExit(status);
        }
    }

    private static FixtureInput readUserInput(DurableExecutionInput input) {
        var operation = input.initialExecutionState().operations().stream()
                .filter(op -> op.type() == OperationType.EXECUTION)
                .findFirst()
                .orElseThrow();
        return JSON.deserialize(operation.executionDetails().inputPayload(), TypeToken.get(FixtureInput.class));
    }

    String handle(FixtureInput input, DurableContext context, InvocationTrace trace) {
        trace.rootEnter();
        try {
            if (!context.isReplaying()
                    && input.targetEnvironment() != null
                    && !input.targetEnvironment().equals(InvocationTrace.ENVIRONMENT)) {
                trace.event("PLACEMENT_MISS");
                return "PLACEMENT_MISS";
            }
            return runScenario(input, context, trace);
        } finally {
            if ("suspend".equals(input.scenario())) {
                trace.event("CLEANUP_ENTER");
                boundedPause(2000);
                trace.event("CLEANUP_EXIT");
            }
            trace.rootExit();
        }
    }

    private String runScenario(FixtureInput input, DurableContext context, InvocationTrace trace) {
        return switch (input.scenario()) {
            case "baseline", "replay", "suspend" -> replay(input, context, trace);
            case "hold", "probe" -> context.step("held-step", String.class, step -> hold(input, trace), NO_RETRY);
            case "timeout", "failure-inflight", "return-inflight" -> inFlight(input, context, trace);
            case "stubborn" -> stubborn(input, context, trace);
            case "fixed", "nested" -> fixed(input, context, trace);
            case "success" -> context.step("success", String.class, step -> body(trace, "success", input.marker()));
            case "failure" -> context.step("failure", String.class, step -> fail(trace, input.marker()), NO_RETRY);
            default -> throw new IllegalArgumentException("Unknown fixture scenario");
        };
    }

    private String replay(FixtureInput input, DurableContext context, InvocationTrace trace) {
        var value = context.step("success", String.class, step -> body(trace, "success", input.marker()));
        try {
            context.step("failure", String.class, step -> fail(trace, input.marker()), NO_RETRY);
        } catch (IllegalStateException expected) {
            trace.event("STORED_FAILURE", Map.of("message", expected.getMessage()));
        }
        context.wait("resume", Duration.ofSeconds(3));
        return context.step("after-resume", String.class, step -> body(trace, "after-resume", value));
    }

    private static String body(InvocationTrace trace, String name, String value) {
        trace.taskEnter(name);
        try {
            trace.event("BODY", Map.of("name", name, "effectKey", trace.executionArn + "/" + name, "value", value));
            return value;
        } finally {
            trace.taskExit(name);
        }
    }

    private static String fail(InvocationTrace trace, String marker) {
        body(trace, "failure", marker);
        throw new IllegalStateException("expected:" + marker);
    }

    private String inFlight(FixtureInput input, DurableContext context, InvocationTrace trace) {
        var entered = new CountDownLatch(1);
        context.stepAsync("inflight", String.class, step -> blocked(input, trace, entered), NO_RETRY);
        await(entered, 5000);
        trace.event("ROOT_RESULT");
        if ("failure-inflight".equals(input.scenario())) {
            throw new IllegalStateException("expected:" + input.marker());
        }
        return input.marker();
    }

    private static String blocked(FixtureInput input, InvocationTrace trace, CountDownLatch entered) {
        trace.taskEnter("inflight");
        entered.countDown();
        try {
            if (!new CountDownLatch(1).await(Math.min(input.holdMillis(), 120_000), TimeUnit.MILLISECONDS)) {
                trace.event(
                        "timeout".equals(input.scenario()) ? "ESCAPE" : "WORK_COMPLETED",
                        Map.of("reason", "bounded task completed"));
            }
            return input.marker();
        } catch (InterruptedException interrupted) {
            trace.event("INTERRUPTED");
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fixture interrupted", interrupted);
        } finally {
            trace.taskExit("inflight");
        }
    }

    private String stubborn(FixtureInput input, DurableContext context, InvocationTrace trace) {
        var entered = new CountDownLatch(1);
        context.runInChildContextAsync("stubborn-child", String.class, child -> {
            trace.taskEnter("stubborn-child");
            entered.countDown();
            try {
                var end = System.nanoTime() + Math.min(input.holdMillis(), 120_000) * 1_000_000L;
                while (System.nanoTime() < end) {
                    try {
                        new CountDownLatch(1).await(200, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        trace.event("IGNORED_INTERRUPT");
                    }
                }
                trace.event("RESIDUAL_EXIT");
                try {
                    child.step("late-work", String.class, step -> body(trace, "late-work", input.marker()));
                    trace.event("LATE_ACCEPTED");
                } catch (Throwable rejected) {
                    trace.event(
                            "LATE_REJECTED",
                            Map.of("errorType", rejected.getClass().getSimpleName()));
                    throw rejected;
                }
                return input.marker();
            } finally {
                trace.taskExit("stubborn-child");
            }
        });
        await(entered, 5000);
        return input.marker();
    }

    private String fixed(FixtureInput input, DurableContext context, InvocationTrace trace) {
        var pool = (ThreadPoolExecutor) shared.getExecutorService();
        var barrier = BARRIERS.computeIfAbsent(input.cohort(), key -> {
            ESCAPES.schedule(() -> BARRIERS.remove(key), 20, TimeUnit.SECONDS);
            return new CountDownLatch(input.peers());
        });
        trace.event("BARRIER_ENTER");
        barrier.countDown();
        await(barrier, 8000);
        trace.event("BARRIER_PASSED");
        var escape = ESCAPES.schedule(
                () -> {
                    trace.snapshot(pool);
                    trace.event("ESCAPE", Map.of("reason", "fixed executor failed to progress"));
                    pool.setMaximumPoolSize(32);
                    pool.setCorePoolSize(32); // Test-only escape after the progress budget.
                },
                8,
                TimeUnit.SECONDS);
        try {
            var value = context.step("success", String.class, step -> body(trace, "success", input.marker()));
            if ("nested".equals(input.scenario())) {
                nested(context, trace, value);
            }
            trace.event("PROGRESS");
            return value;
        } finally {
            escape.cancel(false);
        }
    }

    private static void nested(DurableContext context, InvocationTrace trace, String value) {
        context.runInChildContext("child", String.class, child -> {
            trace.taskEnter("child");
            try {
                var mapped = child.map(
                        "map",
                        List.of(value, value),
                        String.class,
                        (item, index, branch) ->
                                branch.step("mapped-step", String.class, step -> body(trace, "mapped-step", item)));
                try (var parallel = child.parallel("parallel")) {
                    parallel.branch(
                            "left", String.class, branch -> branch.step("left-step", String.class, step -> value));
                    parallel.branch(
                            "right", String.class, branch -> branch.step("right-step", String.class, step -> value));
                    parallel.get();
                }
                return mapped.getResult(0);
            } finally {
                trace.taskExit("child");
            }
        });
    }

    private static String hold(FixtureInput input, InvocationTrace trace) {
        trace.taskEnter("held-step");
        var end = System.nanoTime() + Math.min(input.holdMillis(), 120_000) * 1_000_000L;
        try {
            while (System.nanoTime() < end) {
                var request = HttpRequest.newBuilder(URI.create(input.controlUrl()))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                try {
                    var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Control object HTTP " + response.statusCode());
                    }
                    if (response.body().trim().equals("release")) {
                        return input.marker();
                    }
                } catch (IOException failure) {
                    throw new IllegalStateException("Control object read failed", failure);
                }
                trace.event("HEARTBEAT");
                new CountDownLatch(1).await(500, TimeUnit.MILLISECONDS);
            }
            trace.event("ESCAPE", Map.of("reason", "held step reached escape"));
            throw new IllegalStateException("Placement/control deadline expired");
        } catch (InterruptedException interrupted) {
            trace.event("INTERRUPTED");
            Thread.currentThread().interrupt();
            throw new IllegalStateException("healthy holder interrupted", interrupted);
        } finally {
            trace.taskExit("held-step");
        }
    }

    private static void await(CountDownLatch latch, long millis) {
        try {
            if (!latch.await(millis, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("PLACEMENT_PRECONDITION: barrier not established");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fixture interrupted", interrupted);
        }
    }

    private static void boundedPause(long millis) {
        try {
            new CountDownLatch(1).await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
