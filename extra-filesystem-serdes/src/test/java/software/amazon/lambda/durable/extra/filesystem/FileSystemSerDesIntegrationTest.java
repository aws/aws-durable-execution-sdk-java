// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extra.filesystem;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.model.ExecutionStatus.PENDING;
import static software.amazon.lambda.durable.model.ExecutionStatus.SUCCEEDED;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.ParallelBranchConfig;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class FileSystemSerDesIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void replaysLargeGenericStepResultWithoutRepeatingUserCode() {
        var serDes = new TrackingSerDes(directory);
        var calls = new AtomicInteger();
        var invocations = new AtomicInteger();
        var type = new TypeToken<List<String>>() {};
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            invocations.incrementAndGet();
            var future = ctx.stepAsync(
                    "large",
                    type,
                    step -> {
                        calls.incrementAndGet();
                        return List.of("x".repeat(400000), input);
                    },
                    StepConfig.builder().serDes(serDes).build());
            var result = future.get();
            assertEquals(result, future.get());
            ctx.wait("resume", Duration.ofSeconds(1));
            return result.get(0).length() + result.get(1).length();
        });
        var result = runner.runUntilComplete("input");
        assertEquals(SUCCEEDED, result.getStatus());
        assertEquals(400005, result.getResult(Integer.class));
        assertEquals(1, calls.get());
        assertTrue(invocations.get() > 1);
        assertEquals(1, serDes.writes.size());
        var stored = serDes.writes.get(0);
        assertEquals(
                "operation/" + result.getOperation("large").getId() + "/result",
                stored.context().entityId());
        assertTrue(serDes.reads.stream().allMatch(stored.context()::equals));
        assertEquals(
                stored.payload(), result.getOperation("large").getStepDetails().result());
    }

    @Test
    void reconstructsOffloadedExceptionsOnReplay() {
        var serDes = new TrackingSerDes(directory);
        var calls = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> ctx.step(
                            "failure",
                            String.class,
                            step -> {
                                calls.incrementAndGet();
                                throw new IllegalArgumentException("invalid input");
                            },
                            StepConfig.builder()
                                    .serDes(serDes)
                                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                    .build()));
            ctx.wait("resume", Duration.ofSeconds(1));
            return failure.getMessage();
        });
        var result = runner.runUntilComplete("input");
        assertEquals(SUCCEEDED, result.getStatus());
        assertEquals("invalid input", result.getResult(String.class));
        assertEquals(1, calls.get());
        assertEquals(1, serDes.writes.size());
        assertTrue(serDes.writes.get(0).context().entityId().endsWith("/exception"));
        assertEquals(
                serDes.writes.get(0).payload(),
                result.getOperation("failure").getError().errorData());
    }

    @Test
    void retryKeepsExceptionAndSuccessPayloadsIndependentlyReadable() {
        var serDes = new TrackingSerDes(directory);
        var calls = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.step(
                        "retry",
                        String.class,
                        step -> {
                            if (calls.incrementAndGet() == 1) throw new IllegalArgumentException("try again");
                            return "success";
                        },
                        StepConfig.builder()
                                .serDes(serDes)
                                .retryStrategy((error, attempt) ->
                                        attempt < 2 ? RetryDecision.retry(Duration.ofSeconds(1)) : RetryDecision.fail())
                                .build()));
        assertEquals("success", runner.runUntilComplete("input").getResult(String.class));
        assertEquals(2, calls.get());
        assertEquals(2, serDes.writes.size());
        var failure = serDes.writes.get(0);
        var success = serDes.writes.get(1);
        assertNotEquals(failure.context().entityId(), success.context().entityId());
        assertEquals(
                "try again",
                serDes.deserialize(failure.payload(), TypeToken.get(IllegalArgumentException.class), failure.context())
                        .getMessage());
        assertEquals("success", serDes.deserialize(success.payload(), TypeToken.get(String.class), success.context()));
    }

    @Test
    void pollingRetainsAllCheckpointedStatesAcrossSuspension() {
        var serDes = new TrackingSerDes(directory);
        var calls = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.waitForCondition(
                        "poll",
                        Integer.class,
                        (state, step) -> {
                            calls.incrementAndGet();
                            return state == 2
                                    ? WaitForConditionResult.stopPolling(3)
                                    : WaitForConditionResult.continuePolling(state + 1);
                        },
                        WaitForConditionConfig.<Integer>builder()
                                .initialState(0)
                                .serDes(serDes)
                                .waitStrategy((state, attempt) -> Duration.ofSeconds(1))
                                .build()));
        var result = runner.runUntilComplete("input");
        assertEquals(SUCCEEDED, result.getStatus());
        assertEquals(3, result.getResult(Integer.class));
        assertEquals(3, calls.get());
        assertEquals(4, serDes.writes.size());
        for (int state = 0; state <= 3; state++) {
            var stored = serDes.writes.get(state);
            assertEquals(state, serDes.deserialize(stored.payload(), TypeToken.get(Integer.class), stored.context()));
        }
        assertEquals(1, serDes.writes.stream().map(Stored::context).distinct().count());
        assertEquals(4, serDes.writes.stream().map(Stored::payload).distinct().count());
    }

    @Test
    void childMapAndParallelBranchesReceiveDistinctIdentities() {
        var serDes = new TrackingSerDes(directory);
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var child = ctx.runInChildContext(
                    "child",
                    String.class,
                    nested -> input,
                    RunInChildContextConfig.builder().serDes(serDes).build());
            var map = ctx.map(
                    "map",
                    List.of("a", "b"),
                    String.class,
                    (item, index, nested) -> item,
                    MapConfig.builder().serDes(serDes).build());
            var parallel = ctx.parallel("parallel");
            var futures = new ArrayList<DurableFuture<String>>();
            try (parallel) {
                for (var value : List.of("c", "d")) {
                    futures.add(parallel.branch(
                            "branch-" + value,
                            String.class,
                            nested -> value,
                            ParallelBranchConfig.builder().serDes(serDes).build()));
                }
            }
            parallel.get();
            ctx.wait("resume", Duration.ofSeconds(1));
            return child
                    + String.join("", map.results())
                    + futures.get(0).get()
                    + futures.get(1).get();
        });
        var result = runner.runUntilComplete("input");
        assertEquals(SUCCEEDED, result.getStatus());
        assertEquals("inputabcd", result.getResult(String.class));
        assertTrue(serDes.writes.stream().map(Stored::context).distinct().count() >= 5);
        assertTrue(serDes.writes.stream()
                .allMatch(write -> write.context().entityId().endsWith("/result")));
    }

    @Test
    void callbackConsumesAnExplicitlyEncodedExternalEnvelope() {
        var serDes = new TrackingSerDes(directory);
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.createCallback(
                                "callback",
                                String.class,
                                CallbackConfig.builder().serDes(serDes).build())
                        .get());
        assertEquals(PENDING, runner.run("input").getStatus());
        var producer = new SerDesContext("external-producer", "approval");
        runner.completeCallback(runner.getCallbackId("callback"), serDes.serialize("approved", producer));
        var result = runner.runUntilComplete("input");
        assertEquals("approved", result.getResult(String.class));
        assertEquals(
                "operation/" + result.getOperation("callback").getId() + "/result",
                serDes.reads.get(0).entityId());
        assertNotEquals(producer, serDes.reads.get(0));
    }

    @Test
    void invokeSeparatesPayloadAndResultIdentity() {
        var serDes = new TrackingSerDes(directory);
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.invoke(
                        "invoke",
                        "target",
                        input,
                        String.class,
                        InvokeConfig.builder()
                                .payloadSerDes(serDes)
                                .serDes(serDes)
                                .build()));
        assertEquals(PENDING, runner.run("input").getStatus());
        var payload = serDes.writes.get(0);
        assertTrue(payload.context().entityId().endsWith("/invoke-payload"));
        assertEquals("input", serDes.deserialize(payload.payload(), TypeToken.get(String.class), payload.context()));
        serDes.reads.clear();
        runner.completeChainedInvoke("invoke", serDes.serialize("output", new SerDesContext("callee", "output")));
        assertEquals("output", runner.runUntilComplete("input").getResult(String.class));
        assertTrue(serDes.reads.get(0).entityId().endsWith("/result"));
        assertEquals(
                payload.context().durableExecutionArn(), serDes.reads.get(0).durableExecutionArn());
    }

    @Test
    void legacySerializerDefaultOverloadsRemainCompatible() {
        var legacy = new JacksonSerDes();
        var context = new SerDesContext("execution", "entity");
        assertEquals(
                "value", legacy.deserialize(legacy.serialize("value", context), TypeToken.get(String.class), context));
    }

    private record Stored(SerDesContext context, String payload) {}

    private static final class TrackingSerDes implements SerDes {
        private final FileSystemSerDes delegate;
        private final List<Stored> writes = new CopyOnWriteArrayList<>();
        private final List<SerDesContext> reads = new CopyOnWriteArrayList<>();

        private TrackingSerDes(Path directory) {
            delegate = FileSystemSerDes.builder(directory).build();
        }

        @Override
        public String serialize(Object value) {
            throw new AssertionError("Operation serialization must supply context");
        }

        @Override
        public <T> T deserialize(String data, TypeToken<T> type) {
            throw new AssertionError("Operation deserialization must supply context");
        }

        @Override
        public String serialize(Object value, SerDesContext context) {
            var serialized = delegate.serialize(value, context);
            writes.add(new Stored(context, serialized));
            return serialized;
        }

        @Override
        public <T> T deserialize(String data, TypeToken<T> type, SerDesContext context) {
            reads.add(context);
            return delegate.deserialize(data, type, context);
        }
    }
}
