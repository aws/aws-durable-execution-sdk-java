// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.SuspendExecutionException;
import com.amazonaws.lambda.durable.retry.RetryStrategies;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.*;

class DurableContextTest {
    private static final Logger logger = LoggerFactory.getLogger(DurableContextTest.class);

    private DurableContext createTestContext() {
        var executionOp = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .build();
        return createTestContext(List.of(executionOp));
    }

    private DurableContext createTestContext(List<Operation> initialOperations) {
        var client = TestUtils.createMockClient();
        var initialExecutionState = CheckpointUpdatedExecutionState.builder()
                .operations(initialOperations)
                .build();
        var executionManager = new ExecutionManager(
                "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST/durable-execution/"
                        + "349beff4-a89d-4bc8-a56f-af7a8af67a5f/20dae574-53da-37a1-bfd5-b0e2e6ec715d",
                "test-token",
                initialExecutionState,
                DurableConfig.builder().withDurableExecutionClient(client).build());
        return new DurableContext(executionManager, DurableConfig.builder().build(), null);
    }

    @Test
    void testContextCreation() {
        logger.info("testContextCreation");
        var context = createTestContext();

        assertNotNull(context);
        assertNull(context.getLambdaContext());
    }

    @Test
    void testGetExecutionContext() {
        logger.info("testGetExecutionContext");
        var context = createTestContext();

        var executionContext = context.getExecutionContext();

        assertNotNull(executionContext);
        assertNotNull(executionContext.getDurableExecutionArn());
        assertEquals(
                "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST/durable-execution/"
                        + "349beff4-a89d-4bc8-a56f-af7a8af67a5f/20dae574-53da-37a1-bfd5-b0e2e6ec715d",
                executionContext.getDurableExecutionArn());
    }

    @Test
    void testStepExecution() {
        logger.info("testStepExecution");
        var context = createTestContext();

        var result = context.step("test", String.class, () -> "Hello World");

        assertEquals("Hello World", result);
    }

    @Test
    void testStepReplay() {
        logger.info("testStepReplay");
        // Create context with existing operation
        var existingOp = Operation.builder()
                .id("1")
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"Cached Result\"").build())
                .build();
        var context = createTestContext(List.of(existingOp));

        // This should return cached result, not execute the function
        var result = context.step("test", String.class, () -> "New Result");

        assertEquals("Cached Result", result);
    }

    @Test
    void testStepAsync() throws Exception {
        logger.info("testStepAsync");
        var context = createTestContext();

        var future = context.stepAsync("async-test", String.class, () -> "Async Result");

        assertNotNull(future);
        assertEquals("Async Result", future.get());
    }

    @Test
    void testStepAsyncReplay() throws Exception {
        logger.info("testStepAsyncReplay");
        // Create context with existing operation
        var existingOp = Operation.builder()
                .id("1")
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(
                        StepDetails.builder().result("\"Cached Async Result\"").build())
                .build();
        var context = createTestContext(List.of(existingOp));

        // This should return cached result immediately
        var future = context.stepAsync("async-test", String.class, () -> "New Async Result");
        assertEquals("Cached Async Result", future.get());
    }

    @Test
    void testWait() {
        logger.info("testWait");
        var context = createTestContext();

        // Wait should throw SuspendExecutionException
        assertThrows(SuspendExecutionException.class, () -> {
            context.wait(Duration.ofMinutes(5));
        });
    }

    @Test
    void testWaitReplay() {
        logger.info("testWaitReplay");
        // Create context with completed wait operation
        var existingOp =
                Operation.builder().id("1").status(OperationStatus.SUCCEEDED).build();
        var context = createTestContext(List.of(existingOp));

        // Wait should complete immediately (no exception)
        assertDoesNotThrow(() -> {
            context.wait(Duration.ofMinutes(5));
        });
    }

    @Test
    void testCombinedSyncAsyncWait() throws Exception {
        logger.info("testCombinedSyncAsyncWait");
        var context = createTestContext();

        // Execute sync step
        var syncResult = context.step("sync-step", String.class, () -> "Sync Done");
        assertEquals("Sync Done", syncResult);
        logger.info("testCombinedSyncAsyncWait - Sync Done");

        // Execute async step
        var asyncFuture = context.stepAsync("async-step", Integer.class, () -> 42);
        assertEquals(42, asyncFuture.get());
        logger.info("testCombinedSyncAsyncWait - Async Done");

        // Wait should suspend (throw exception)
        assertThrows(SuspendExecutionException.class, () -> {
            context.wait(Duration.ofSeconds(30));
            logger.info("testCombinedSyncAsyncWait - Wait Done");
        });
    }

    @Test
    void testCombinedReplay() throws Exception {
        logger.info("testCombinedReplay");
        // Create context with all operations completed
        var syncOp = Operation.builder()
                .id("1")
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"Replayed Sync\"").build())
                .build();
        var asyncOp = Operation.builder()
                .id("2")
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("100").build())
                .build();
        var waitOp =
                Operation.builder().id("3").status(OperationStatus.SUCCEEDED).build();
        var context = createTestContext(List.of(syncOp, asyncOp, waitOp));

        // All operations should replay from cache
        var syncResult = context.step("sync-step", String.class, () -> "New Sync");
        assertEquals("Replayed Sync", syncResult);

        var asyncFuture = context.stepAsync("async-step", Integer.class, () -> 999);
        assertEquals(100, asyncFuture.get());

        // Wait should complete immediately (no exception)
        assertDoesNotThrow(() -> {
            context.wait(Duration.ofSeconds(30));
        });
    }

    @Test
    void testNamedWait() {
        logger.info("testNamedWait");
        var ctx = createTestContext();

        // Named wait should throw SuspendExecutionException
        assertThrows(SuspendExecutionException.class, () -> {
            ctx.wait("my-wait", Duration.ofSeconds(5));
        });

        // Verify it works without error (basic functionality test)
        assertDoesNotThrow(() -> {
            var ctx2 = createTestContext();
            try {
                ctx2.wait("another-wait", Duration.ofMinutes(1));
            } catch (SuspendExecutionException e) {
                // Expected - this means the method worked
            }
        });
    }

    @Test
    void testStepWithTypeToken() {
        logger.info("testStepWithTypeToken");
        var context = createTestContext();

        List<String> result = context.step("test-list", new TypeToken<List<String>>() {}, () -> List.of("a", "b", "c"));

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("a", result.get(0));
    }

    @Test
    void testStepWithTypeTokenReplay() {
        logger.info("testStepWithTypeTokenReplay");
        // Create context with existing operation
        var existingOp = Operation.builder()
                .id("1")
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder()
                        .result("[\"cached1\",\"cached2\"]")
                        .build())
                .build();
        var context = createTestContext(List.of(existingOp));

        // This should return cached result, not execute the function
        List<String> result =
                context.step("test-list", new TypeToken<List<String>>() {}, () -> List.of("new1", "new2"));

        assertEquals(2, result.size());
        assertEquals("cached1", result.get(0));
        assertEquals("cached2", result.get(1));
    }

    @Test
    void testStepWithTypeTokenAndConfig() {
        logger.info("testStepWithTypeTokenAndConfig");
        var context = createTestContext();

        List<Integer> result = context.step(
                "test-numbers",
                new TypeToken<List<Integer>>() {},
                () -> List.of(1, 2, 3),
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.DEFAULT)
                        .build());

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals(1, result.get(0));
    }

    @Test
    void testStepAsyncWithTypeToken() throws Exception {
        logger.info("testStepAsyncWithTypeToken");
        var context = createTestContext();

        DurableFuture<List<String>> future =
                context.stepAsync("async-list", new TypeToken<List<String>>() {}, () -> List.of("x", "y", "z"));

        assertNotNull(future);
        List<String> result = future.get();
        assertEquals(3, result.size());
        assertEquals("x", result.get(0));
    }

    @Test
    void testStepAsyncWithTypeTokenReplay() throws Exception {
        logger.info("testStepAsyncWithTypeTokenReplay");
        // Create context with existing operation
        var existingOp = Operation.builder()
                .id("1")
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder()
                        .result("[\"async-cached1\",\"async-cached2\"]")
                        .build())
                .build();
        var context = createTestContext(List.of(existingOp));

        // This should return cached result immediately
        DurableFuture<List<String>> future = context.stepAsync(
                "async-list", new TypeToken<List<String>>() {}, () -> List.of("async-new1", "async-new2"));

        List<String> result = future.get();
        assertEquals(2, result.size());
        assertEquals("async-cached1", result.get(0));
        assertEquals("async-cached2", result.get(1));
    }

    @Test
    void testStepAsyncWithTypeTokenAndConfig() throws Exception {
        logger.info("testStepAsyncWithTypeTokenAndConfig");
        var context = createTestContext();

        DurableFuture<List<Integer>> future = context.stepAsync(
                "async-numbers",
                new TypeToken<List<Integer>>() {},
                () -> List.of(10, 20, 30),
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.Presets.DEFAULT)
                        .build());

        assertNotNull(future);
        List<Integer> result = future.get();
        assertEquals(3, result.size());
        assertEquals(10, result.get(0));
    }
}
