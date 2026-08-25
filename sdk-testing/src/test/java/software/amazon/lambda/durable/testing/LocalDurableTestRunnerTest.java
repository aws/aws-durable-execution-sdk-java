// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.TypeToken.get;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.serde.Base64StringBinaryCodec;
import software.amazon.lambda.durable.serde.BinarySerDes;
import software.amazon.lambda.durable.serde.ComposableBinarySerDesStage;
import software.amazon.lambda.durable.serde.FileSystemSerDes;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesStage;
import software.amazon.lambda.durable.serde.Utf8StringBinaryCodec;

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

    @Test
    void checkpointedLargeOutputReplaysWithoutDuplicateExecutionOperation() {
        var stepExecutions = new AtomicInteger();
        var largeResult = "x".repeat(7 * 1024 * 1024);
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
                    context.step("once", Void.class, step -> {
                        stepExecutions.incrementAndGet();
                        return null;
                    });
                    return largeResult;
                })
                .withOutputType(String.class);

        var firstResult = runner.run("test");
        var replayResult = runner.run("test");

        assertEquals(ExecutionStatus.SUCCEEDED, firstResult.getStatus());
        assertEquals(largeResult, firstResult.getResult());
        assertEquals(ExecutionStatus.SUCCEEDED, replayResult.getStatus());
        assertEquals(largeResult, replayResult.getResult());
        assertEquals(1, stepExecutions.get());
    }

    @Test
    void filesystemPersistedSerDesUsesDefaultJacksonInputCodec(@TempDir Path basePath) {
        var config = DurableConfig.builder()
                .withSerDes(new JacksonSerDes()
                        .then(FileSystemSerDes.builder(basePath).build()))
                .build();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> input, config)
                .withOutputType(String.class);

        var result = runner.run("value");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("value", result.getResult());
    }

    @Test
    void plainPersistedSerDesIsUsedAsDefaultInputCodec() {
        var config = DurableConfig.builder().withSerDes(prefixedStringSerDes()).build();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> input, config)
                .withOutputType(String.class);

        var result = runner.run("value");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("value", result.getResult());
    }

    @Test
    void rejectsComposableInputSerDes(@TempDir Path basePath) {
        var config = DurableConfig.builder()
                .withSerDes(new JacksonSerDes()
                        .then(FileSystemSerDes.builder(basePath).build()))
                .build();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> input, config);

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> runner.withInputSerDes(new JacksonSerDes().then(wrappingStage(new AtomicInteger()))));

        assertTrue(failure.getMessage().contains("value codec"));
    }

    @Test
    void defaultInputCodecBypassesPersistedStagesBeforeFileSystemSerDes(@TempDir Path basePath) {
        var deserializeCalls = new AtomicInteger();
        var persistedSerDes = new JacksonSerDes()
                .then(bytesStage(deserializeCalls))
                .then(FileSystemSerDes.builder(basePath).build());
        var config = DurableConfig.builder().withSerDes(persistedSerDes).build();
        var runner = LocalDurableTestRunner.create(
                        String.class, (input, context) -> input + ":" + deserializeCalls.get(), config)
                .withOutputType(String.class);

        var result = runner.run("value");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("value:0", result.getResult());
    }

    private static SerDesStage wrappingStage(AtomicInteger deserializeCalls) {
        return new SerDesStage() {
            @Override
            public String serialize(String value) {
                return "<" + value + ">";
            }

            @Override
            public String deserialize(String data) {
                deserializeCalls.incrementAndGet();
                return data.substring(1, data.length() - 1);
            }
        };
    }

    private static SerDesStage bytesStage(AtomicInteger deserializeCalls) {
        return ComposableBinarySerDesStage.builder()
                .startWith(Utf8StringBinaryCodec.INSTANCE)
                .then(new BinarySerDes() {
                    @Override
                    public byte[] serialize(byte[] value) {
                        return value;
                    }

                    @Override
                    public byte[] deserialize(byte[] data) {
                        deserializeCalls.incrementAndGet();
                        return data;
                    }
                })
                .endWith(Base64StringBinaryCodec.INSTANCE)
                .build();
    }

    private static SerDes prefixedStringSerDes() {
        return new SerDes() {
            @Override
            public String serialize(Object value) {
                return "custom:" + value;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                if (!TypeToken.get(String.class).equals(typeToken) || !data.startsWith("custom:")) {
                    throw new SerDesException("Invalid custom string payload");
                }
                return (T) data.substring("custom:".length());
            }
        };
    }
}
