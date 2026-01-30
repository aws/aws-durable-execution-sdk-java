// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.SuspendExecutionException;
import com.amazonaws.lambda.durable.model.DurableExecutionInput.InitialExecutionState;
import com.amazonaws.lambda.durable.retry.RetryStrategies;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.*;

/**
 * Unit tests for DurableContext.
 *
 * <p>Note: Async step execution tests (stepAsync with first execution) are tested in integration tests using
 * LocalDurableTestRunner, which properly handles the thread coordination required for async operations. This test class
 * focuses on synchronous operations and replay scenarios which can be reliably tested at the unit level.
 */
class DurableContextTest {

    private static final String TEST_EXECUTION_ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST/durable-execution/"
                    + "349beff4-a89d-4bc8-a56f-af7a8af67a5f/20dae574-53da-37a1-bfd5-b0e2e6ec715d";

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
        var initialExecutionState = new InitialExecutionState(initialOperations, null);
        var executionManager = new ExecutionManager(TEST_EXECUTION_ARN, "test-token", initialExecutionState, client);
        return new DurableContext(executionManager, DurableConfig.builder().build(), null);
    }

    @Test
    void testContextCreation() {
        var context = createTestContext();

        assertNotNull(context);
        assertNull(context.getLambdaContext());
    }

    @Test
    void testGetExecutionContext() {
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
        var context = createTestContext();

        var result = context.step("test", String.class, () -> "Hello World");

        assertEquals("Hello World", result);
    }

    @Test
    void testStepReplay() {
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
    void testStepAsyncReplay() {
        // Create context with existing completed operation - tests replay behavior
        var existingOp = Operation.builder()
                .id("1")
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(
                        StepDetails.builder().result("\"Cached Async Result\"").build())
                .build();
        var context = createTestContext(List.of(existingOp));

        // This should return cached result immediately without blocking
        var future = context.stepAsync("async-test", String.class, () -> "New Async Result");
        assertEquals("Cached Async Result", future.get());
    }

    @Test
    void testWait() {
        var context = createTestContext();

        // Wait should throw SuspendExecutionException
        assertThrows(SuspendExecutionException.class, () -> {
            context.wait(Duration.ofMinutes(5));
        });
    }

    @Test
    void testWaitReplay() {
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
    void testCombinedReplay() {
        // Create context with all operations completed - tests replay behavior
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
        var context = createTestContext();

        List<String> result = context.step("test-list", new TypeToken<List<String>>() {}, () -> List.of("a", "b", "c"));

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("a", result.get(0));
    }

    @Test
    void testStepWithTypeTokenReplay() {
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
    void testStepAsyncWithTypeTokenReplay() {
        // Create context with existing completed operation - tests replay behavior
        var existingOp = Operation.builder()
                .id("1")
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder()
                        .result("[\"async-cached1\",\"async-cached2\"]")
                        .build())
                .build();
        var context = createTestContext(List.of(existingOp));

        // This should return cached result immediately without blocking
        DurableFuture<List<String>> future = context.stepAsync(
                "async-list", new TypeToken<List<String>>() {}, () -> List.of("async-new1", "async-new2"));

        List<String> result = future.get();
        assertEquals(2, result.size());
        assertEquals("async-cached1", result.get(0));
        assertEquals("async-cached2", result.get(1));
    }
}
