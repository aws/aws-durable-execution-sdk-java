// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.TypeToken.get;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.offload.OffloadedPayload;
import software.amazon.lambda.durable.offload.PayloadOffloadContext;
import software.amazon.lambda.durable.offload.PayloadOffloader;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.serde.SerDes;

class LocalDurableTestRunnerTest {

    @Test
    void testSimpleExecution() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var result = ctx.step("process", String.class, stepCtx -> "Hello, " + input);
            return result;
        });

        var testResult = runner.run("World");

        assertEquals(ExecutionStatus.SUCCEEDED, testResult.getStatus());
        assertEquals("Hello, World", testResult.getResult(String.class));
    }

    @Test
    void testMultipleSteps() {
        var runner = LocalDurableTestRunner.create(Integer.class, (input, ctx) -> {
                    var step1 = ctx.step("add", Integer.class, stepCtx -> input + 10);
                    var step2 = ctx.step("multiply", Integer.class, stepCtx -> step1 * 2);
                    var step3 = ctx.step("subtract", Integer.class, stepCtx -> step2 - 5);
                    return step3;
                })
                .withOutputType(Integer.class);

        var testResult = runner.run(5);

        assertEquals(ExecutionStatus.SUCCEEDED, testResult.getStatus());
        assertEquals(25, testResult.getResult()); // (5 + 10) * 2 - 5 = 25
    }

    @Test
    void testGetOperation() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            ctx.step("step-1", String.class, stepCtx -> "result1");
            ctx.step("step-2", String.class, stepCtx -> "result2");
            return "done";
        });

        runner.run("test");

        var op1 = runner.getOperation("step-1");
        assertNotNull(op1);
        assertEquals("step-1", op1.getName());
        assertEquals("result1", op1.getStepResult(String.class));

        var op2 = runner.getOperation("step-2");
        assertNotNull(op2);
        assertEquals("step-2", op2.getName());
        assertEquals("result2", op2.getStepResult(get(String.class)));
    }

    @Test
    void operationLevelOffloaderIsUsedForResultInspection() {
        var offloader = new InMemoryOffloader();
        var config = DurableConfig.builder().build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.step(
                        "offloaded",
                        String.class,
                        stepCtx -> "operation-result",
                        StepConfig.builder().payloadOffloader(offloader).build()),
                config);

        var result = runner.run("input");

        assertEquals("operation-result", result.getOperation("offloaded").getStepResult(String.class));
        assertEquals("operation-result", runner.getOperation("offloaded").getStepResult(String.class));
        assertTrue(offloader.loadCount.get() >= 2);
    }

    @Test
    void disabledOperationOffloaderKeepsInlineInspectionWithGlobalOffloader() {
        var globalOffloader = new InMemoryOffloader();
        var config =
                DurableConfig.builder().withPayloadOffloader(globalOffloader).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.step(
                        "inline",
                        String.class,
                        stepCtx -> "inline-result",
                        StepConfig.builder()
                                .payloadOffloader(PayloadOffloader.disabled())
                                .build()),
                config);

        var result = runner.run("input");
        var loadsBeforeInspection = globalOffloader.loadCount.get();

        assertEquals("inline-result", result.getOperation("inline").getStepResult(String.class));
        assertEquals(loadsBeforeInspection, globalOffloader.loadCount.get());
    }

    @Test
    void disabledOperationMarkerEnvelopeUsesDisabledPolicyForInspection() {
        var marker = "@aws-durable-payload:v2:{}";
        var globalOffloader = new TransformingInlineOffloader();
        SerDes passThroughSerDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return (String) value;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return (T) data;
            }
        };
        var config = DurableConfig.builder()
                .withSerDes(passThroughSerDes)
                .withPayloadOffloader(globalOffloader)
                .build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> {
                    ctx.step(
                            "inline-marker",
                            String.class,
                            stepCtx -> marker,
                            StepConfig.builder()
                                    .serDes(passThroughSerDes)
                                    .payloadOffloader(PayloadOffloader.disabled())
                                    .build());
                    return "done";
                },
                config);

        var result = runner.run("input");

        assertEquals(marker, result.getOperation("inline-marker").getStepResult(String.class));
        assertEquals(0, globalOffloader.loadCount.get());
    }

    @Test
    void rootOutputIsResolvedOnlyWhenResultIsAccessed() {
        var offloader = new InMemoryOffloader();
        var config = DurableConfig.builder().withPayloadOffloader(offloader).build();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> "result", config);

        var result = runner.run("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(0, offloader.loadCount.get());
        assertEquals("result", result.getResult(String.class));
        assertEquals(1, offloader.loadCount.get());
    }

    @Test
    void historyBackedRootOutputUsesLazyResolver() {
        var offloader = new InlineOffloader();
        var config = DurableConfig.builder().withPayloadOffloader(offloader).build();
        var largeResult = "x".repeat(7 * 1024 * 1024);
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> largeResult, config);

        var result = runner.run("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(0, offloader.loadCount.get());
        assertEquals(largeResult, result.getResult(String.class));
        assertEquals(1, offloader.loadCount.get());
    }

    @Test
    void testGenericTypeInput() {
        var resultType = new TypeToken<ArrayList<String>>() {};
        var runner = LocalDurableTestRunner.create(resultType, (input, ctx) -> {
            return ctx.step("process", resultType, stepCtx -> {
                var reversed = new ArrayList<>(input);
                Collections.reverse(reversed);
                return reversed;
            });
        });

        var testResult = runner.run(new ArrayList<>(List.of("item1", "item2")));

        assertEquals(ExecutionStatus.SUCCEEDED, testResult.getStatus());
        assertEquals(List.of("item2", "item1"), testResult.getResult(resultType));
    }

    @Test
    void executionStartTimeIsStableAcrossInvocations() {
        var executionStartTimes = new ArrayList<Instant>();
        var plugin = new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                executionStartTimes.add(info.executionStartTime());
            }
        };
        var config = DurableConfig.builder().withPlugins(plugin).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    context.wait(null, Duration.ofMinutes(1));
                    return input;
                },
                config);

        var firstResult = runner.run("test");
        runner.advanceTime();
        var secondResult = runner.run("test");

        assertEquals(ExecutionStatus.PENDING, firstResult.getStatus());
        assertEquals(ExecutionStatus.SUCCEEDED, secondResult.getStatus());
        assertEquals(2, executionStartTimes.size());
        assertNotNull(executionStartTimes.get(0));
        assertEquals(executionStartTimes.get(0), executionStartTimes.get(1));
    }

    private static final class InMemoryOffloader implements PayloadOffloader {
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicInteger loadCount = new AtomicInteger();

        @Override
        public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
            var reference = "memory://" + sequence.incrementAndGet();
            values.put(reference, serializedPayload);
            return OffloadedPayload.reference(reference, null);
        }

        @Override
        public String load(OffloadedPayload payload, PayloadOffloadContext context) {
            loadCount.incrementAndGet();
            return values.get(payload.reference());
        }
    }

    private static final class InlineOffloader implements PayloadOffloader {
        private final AtomicInteger loadCount = new AtomicInteger();

        @Override
        public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
            return OffloadedPayload.inline(serializedPayload);
        }

        @Override
        public String load(OffloadedPayload payload, PayloadOffloadContext context) {
            loadCount.incrementAndGet();
            return payload.data();
        }
    }

    private static final class TransformingInlineOffloader implements PayloadOffloader {
        private final AtomicInteger loadCount = new AtomicInteger();

        @Override
        public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
            return OffloadedPayload.inline(
                    Base64.getEncoder().encodeToString(serializedPayload.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public String load(OffloadedPayload payload, PayloadOffloadContext context) {
            loadCount.incrementAndGet();
            return new String(Base64.getDecoder().decode(payload.data()), StandardCharsets.UTF_8);
        }
    }
}
