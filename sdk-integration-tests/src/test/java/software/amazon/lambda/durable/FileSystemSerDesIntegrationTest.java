// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.retry.WaitStrategies;
import software.amazon.lambda.durable.serde.FileSystemSerDes;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class FileSystemSerDesIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void storesStepAndExecutionResultsAcrossReplay() throws Exception {
        var stepRuns = new AtomicInteger();
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var config = DurableConfig.builder().withSerDes(serDes).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var result = context.step("persist", String.class, stepContext -> {
                        stepRuns.incrementAndGet();
                        return input + "-stored";
                    });
                    context.wait("replay", Duration.ofSeconds(1));
                    return result;
                },
                config);

        var result = runner.runUntilComplete("value");

        assertEquals("value-stored", result.getResult(String.class));
        assertEquals("value-stored", result.getOperation("persist").getStepResult(String.class));
        assertEquals(1, stepRuns.get());
        try (var files = Files.walk(tempDir)) {
            assertTrue(files.filter(Files::isRegularFile).count() >= 2);
        }
    }

    @Test
    void operationConfigControlsWhereFilesystemStorageIsUsed() throws Exception {
        var fileSystemSerDes = FileSystemSerDes.builder(tempDir).build();
        var config = DurableConfig.builder().withSerDes(new JacksonSerDes()).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "filesystem-step",
                        String.class,
                        stepContext -> input + "-stored",
                        StepConfig.builder().serDes(fileSystemSerDes).build()),
                config);

        var result = runner.runUntilComplete("value");

        assertEquals("value-stored", result.getResult(String.class));
        try (var files = Files.walk(tempDir)) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void replaysStepWaitForConditionChildAndMapPayloads() throws Exception {
        var stepRuns = new AtomicInteger();
        var pollRuns = new AtomicInteger();
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var config = DurableConfig.builder().withSerDes(serDes).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var stepResult = context.step("load-order", String.class, stepContext -> {
                        stepRuns.incrementAndGet();
                        return input + "-loaded";
                    });
                    var pollResult = context.waitForCondition(
                            "poll-order",
                            Integer.class,
                            (state, stepContext) -> {
                                pollRuns.incrementAndGet();
                                var next = state == null ? 1 : state + 1;
                                return next == 2
                                        ? WaitForConditionResult.stopPolling(next)
                                        : WaitForConditionResult.continuePolling(next);
                            },
                            WaitForConditionConfig.<Integer>builder()
                                    .waitStrategy(WaitStrategies.exponentialBackoff(
                                            5, Duration.ofSeconds(1), Duration.ofSeconds(10), 1, JitterStrategy.NONE))
                                    .build());
                    var childResult =
                            context.runInChildContext("format-order", String.class, child -> stepResult + "-child");
                    var mapResult =
                            context.map("map-order", List.of(1, 2), Integer.class, (item, index, child) -> item * 2);
                    return childResult + "-" + pollResult + "-" + mapResult.results();
                },
                config);

        var result = runner.runUntilComplete("order");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("order-loaded-child-2-[2, 4]", result.getResult(String.class));
        assertEquals(1, stepRuns.get());
        assertEquals(2, pollRuns.get());
        assertEquals("order-loaded", result.getOperation("load-order").getStepResult(String.class));
        assertEnvelopePointsToFile(
                result.getOperation("load-order").getStepDetails().result());
        assertEnvelopePointsToFile(
                result.getOperation("map-order").getContextDetails().result());
    }

    @Test
    void acceptsRawCallbackAndInvokeResultsWithBoundarySpecificSerDes() {
        var fileSystemSerDes = FileSystemSerDes.builder(tempDir).build();
        var config = DurableConfig.builder().withSerDes(fileSystemSerDes).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var approval =
                            context.createCallback("approval", String.class).get();
                    return context.invoke(
                            "notify",
                            "target-function",
                            approval,
                            String.class,
                            InvokeConfig.builder()
                                    .payloadSerDes(new JacksonSerDes())
                                    .serDes(fileSystemSerDes)
                                    .build());
                },
                config);

        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        runner.completeCallback(runner.getCallbackId("approval"), "\"approved\"");
        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        runner.completeChainedInvoke("notify", "\"notified\"");

        var completed = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, completed.getStatus());
        assertEquals("notified", completed.getResult(String.class));
    }

    @Test
    void repeatedGetUsesInvocationDeserializationCache() {
        var resultDeserializations = new AtomicInteger();
        var fileSystemSerDes = FileSystemSerDes.builder(tempDir).build();
        var countingSerDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return fileSystemSerDes.serialize(value);
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                if (typeToken.equals(TypeToken.get(Payload.class)) && SerDesContext.getCurrentContext() != null) {
                    resultDeserializations.incrementAndGet();
                }
                return fileSystemSerDes.deserialize(data, typeToken);
            }
        };
        var config = DurableConfig.builder().withSerDes(countingSerDes).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var future = context.stepAsync("cached-step", Payload.class, stepContext -> new Payload(input));
                    var first = future.get();
                    var second = future.get();
                    assertSame(first, second);
                    return first.value();
                },
                config);

        var result = runner.runUntilComplete("cached");

        assertEquals("cached", result.getResult(String.class));
        assertEquals(1, resultDeserializations.get());
    }

    @Test
    void customExceptionPayloadRoundTripsThroughFilesystem() throws Exception {
        var serDes = FileSystemSerDes.builder(tempDir).build();
        var config = DurableConfig.builder().withSerDes(serDes).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "fail-step",
                        String.class,
                        stepContext -> {
                            throw new CustomFailure("boom");
                        },
                        StepConfig.builder()
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build()),
                config);

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        var operationError = result.getOperation("fail-step").getError();
        assertEquals(CustomFailure.class.getName(), operationError.errorType());
        assertEnvelopePointsToFile(operationError.errorData());
    }

    private void assertEnvelopePointsToFile(String envelope) throws Exception {
        var file = Path.of(MAPPER.readTree(envelope).get("file").textValue());
        assertTrue(Files.exists(file));
        assertTrue(file.startsWith(tempDir));
    }

    record Payload(String value) {}

    public static class CustomFailure extends RuntimeException {
        public CustomFailure() {}

        public CustomFailure(String message) {
            super(message);
        }
    }
}
