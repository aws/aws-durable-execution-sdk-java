// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.extra.filesystem.FileSystemSerDes;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.WaitStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class FileSystemSerDesIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path basePath;

    @Test
    void replaysStepAndWaitForConditionStateFromFilesystem() throws Exception {
        var stepExecutions = new AtomicInteger();
        var pollExecutions = new AtomicInteger();
        var config = DurableConfig.builder()
                .withSerDes(FileSystemSerDes.builder(basePath).build())
                .build();

        var runner = LocalDurableTestRunner.create(
                        String.class,
                        (input, context) -> {
                            var stepResult = context.step("load-order", String.class, stepContext -> {
                                stepExecutions.incrementAndGet();
                                return input + "-loaded";
                            });
                            var waitConfig = WaitForConditionConfig.<Integer>builder()
                                    .waitStrategy(WaitStrategies.exponentialBackoff(
                                            5, Duration.ofSeconds(1), Duration.ofSeconds(10), 1, JitterStrategy.NONE))
                                    .build();
                            var pollResult = context.waitForCondition(
                                    "poll-order",
                                    Integer.class,
                                    (state, stepContext) -> {
                                        pollExecutions.incrementAndGet();
                                        var next = state == null ? 1 : state + 1;
                                        return next == 2
                                                ? WaitForConditionResult.stopPolling(next)
                                                : WaitForConditionResult.continuePolling(next);
                                    },
                                    waitConfig);
                            var childResult = context.runInChildContext(
                                    "format-order", String.class, child -> stepResult + "-child");
                            return childResult + "-" + pollResult;
                        },
                        config)
                .withOutputType(String.class);

        var result = runner.runUntilComplete("order");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("order-loaded-child-2", result.getResult());
        assertEquals(1, stepExecutions.get());
        assertEquals(2, pollExecutions.get());
        assertEquals("order-loaded", result.getOperation("load-order").getStepResult(String.class));

        var stepEnvelope = result.getOperation("load-order").getStepDetails().result();
        var stepFile = Path.of(MAPPER.readTree(stepEnvelope).get("file").textValue());
        assertTrue(Files.exists(stepFile));
        assertTrue(stepFile.startsWith(basePath));
    }
}
