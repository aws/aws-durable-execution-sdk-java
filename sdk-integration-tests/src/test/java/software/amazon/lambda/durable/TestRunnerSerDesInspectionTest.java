// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.model.ExecutionStatus.PENDING;
import static software.amazon.lambda.durable.model.ExecutionStatus.SUCCEEDED;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.serde.FileSystemSerDes;
import software.amazon.lambda.durable.serde.FileSystemStorageMode;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class TestRunnerSerDesInspectionTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(FileSystemStorageMode.class)
    void decodesResultSnapshotsAndDirectLookupsAcrossReplay(FileSystemStorageMode mode) {
        var contexts = new CopyOnWriteArrayList<SerDesContext>();
        var files = FileSystemSerDes.builder(directory)
                .storageMode(mode)
                .delegate(recordingSerDes(contexts))
                .build();
        var calls = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            ctx.step(
                    "offloaded",
                    String.class,
                    step -> {
                        calls.incrementAndGet();
                        return "payload";
                    },
                    StepConfig.builder().serDes(files).build());
            ctx.step("ordinary", String.class, step -> "ordinary result");
            ctx.wait("resume", Duration.ofSeconds(1));
            return "handler output";
        });

        var first = runner.run("normal input");
        assertEquals(PENDING, first.getStatus());
        var expectedContext = contexts.get(0);
        contexts.clear();
        assertEquals("payload", first.getOperation("offloaded").getStepResult(String.class, files));
        assertEquals("payload", runner.getOperation("offloaded").getStepResult(TypeToken.get(String.class), files));
        assertTrue(contexts.stream().allMatch(expectedContext::equals));
        assertEquals("ordinary result", first.getOperation("ordinary").getStepResult(String.class));

        var completed = runner.runUntilComplete("normal input");
        assertEquals(SUCCEEDED, completed.getStatus());
        assertEquals("handler output", completed.getResult(String.class));
        assertEquals("payload", completed.getOperation("offloaded").getStepResult(String.class, files));
        assertEquals("payload", first.getOperation("offloaded").getStepResult(String.class, files));
        assertEquals(1, calls.get());
        assertTrue(contexts.stream().allMatch(expectedContext::equals));
    }

    @Test
    void decodesGenericResultsInChildContexts() {
        var files = FileSystemSerDes.builder(directory).build();
        var type = new TypeToken<Map<String, List<Integer>>>() {};
        var payload = Map.of("values", List.of(1, 2, 3));
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.runInChildContext(
                        "child",
                        Integer.class,
                        child -> child.step(
                                        "nested",
                                        type,
                                        step -> payload,
                                        StepConfig.builder().serDes(files).build())
                                .get("values")
                                .size()));

        var result = runner.runUntilComplete("input");
        assertEquals(3, result.getResult(Integer.class));
        var nested = result.getOperation("nested");
        assertEquals(payload, nested.getStepResult(type, files));
        assertTrue(nested.getStepResult(Map.class).containsKey("file"));
    }

    @Test
    void runnerDefaultSerializerReceivesContextForOperationInspectionOnly() {
        var contexts = new CopyOnWriteArrayList<SerDesContext>();
        var config =
                DurableConfig.builder().withSerDes(recordingSerDes(contexts)).build();
        var runner = LocalDurableTestRunner.create(
                String.class, (input, ctx) -> ctx.step("step", String.class, step -> input), config);
        var result = runner.runUntilComplete("value");
        var expectedContext = contexts.get(0);
        contexts.clear();

        assertEquals("value", result.getResult(String.class));
        assertTrue(contexts.isEmpty());
        assertEquals("value", result.getOperation("step").getStepResult(String.class));
        assertEquals("value", runner.getOperation("step").getStepResult(String.class));
        assertEquals(List.of(expectedContext, expectedContext), contexts);
    }

    private static JacksonSerDes recordingSerDes(List<SerDesContext> contexts) {
        return new JacksonSerDes() {
            @Override
            public <T> T deserialize(String data, TypeToken<T> type, SerDesContext context) {
                contexts.add(context);
                return super.deserialize(data, type);
            }
        };
    }
}
