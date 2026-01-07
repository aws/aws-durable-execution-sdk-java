// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.lambda.durable.model.DurableExecutionInput;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.StepDetails;

class DurableExecutionTest {

    private DurableConfig configWithMockClient() {
        return DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .build();
    }

    @Test
    void testExecuteSuccess() {
        var executionOp = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "token1",
                new DurableExecutionInput.InitialExecutionState(List.of(executionOp), null));

        var output = DurableExecutor.execute(
                input,
                null,
                String.class,
                (userInput, ctx) -> ctx.step("test", String.class, () -> "Hello " + userInput),
                configWithMockClient());

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertNotNull(output.result());
        assertTrue(output.result().contains("Hello test-input"));
    }

    @Test
    void testExecutePending() {
        var executionOp = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "token1",
                new DurableExecutionInput.InitialExecutionState(List.of(executionOp), null));

        var output = DurableExecutor.execute(
                input,
                null,
                String.class,
                (userInput, ctx) -> {
                    ctx.step("step1", String.class, () -> "Done");
                    ctx.wait(java.time.Duration.ofSeconds(60));
                    return "Should not reach here";
                },
                configWithMockClient());

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
    }

    @Test
    void testExecuteFailure() {
        var executionOp = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "token1",
                new DurableExecutionInput.InitialExecutionState(List.of(executionOp), null));

        var output = DurableExecutor.execute(
                input,
                null,
                String.class,
                (userInput, ctx) -> {
                    throw new RuntimeException("Test error");
                },
                configWithMockClient());

        assertEquals(ExecutionStatus.FAILED, output.status());
        assertNotNull(output.error());
        assertEquals("RuntimeException", output.error().errorType());
        assertEquals("Test error", output.error().errorMessage());
    }

    @Test
    void testExecuteReplay() {
        var executionOp = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var completedStep = Operation.builder()
                .id("1")
                .name("step1")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"First\"").build())
                .build();

        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "token2",
                new DurableExecutionInput.InitialExecutionState(List.of(executionOp, completedStep), null));

        var output = DurableExecutor.execute(
                input,
                null,
                String.class,
                (userInput, ctx) -> ctx.step("step1", String.class, () -> "Second"),
                configWithMockClient());

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertTrue(output.result().contains("First"));
    }

    @Test
    void testValidationNoOperations() {
        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "token1",
                new DurableExecutionInput.InitialExecutionState(List.of(), null));

        var exception = assertThrows(
                IllegalStateException.class,
                () -> DurableExecutor.execute(
                        input, null, String.class, (userInput, ctx) -> "result", configWithMockClient()));

        assertEquals("First operation must be EXECUTION", exception.getMessage());
    }

    @Test
    void testValidationWrongFirstOperation() {
        var stepOp = Operation.builder()
                .id("1")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"result\"").build())
                .build();

        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "token1",
                new DurableExecutionInput.InitialExecutionState(List.of(stepOp), null));

        var exception = assertThrows(
                IllegalStateException.class,
                () -> DurableExecutor.execute(
                        input, null, String.class, (userInput, ctx) -> "result", configWithMockClient()));

        assertEquals("First operation must be EXECUTION", exception.getMessage());
    }

    @Test
    void testValidationMissingExecutionDetails() {
        var executionOp = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .build();

        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "token1",
                new DurableExecutionInput.InitialExecutionState(List.of(executionOp), null));

        var exception = assertThrows(
                IllegalStateException.class,
                () -> DurableExecutor.execute(
                        input, null, String.class, (userInput, ctx) -> "result", configWithMockClient()));

        assertEquals("EXECUTION operation missing executionDetails", exception.getMessage());
    }

    @Test
    void testExecutorNotShutdownAfterMultipleHandlerInvocations() {
        // Create a config with a shared executor
        var config = configWithMockClient();
        ExecutorService sharedExecutor = config.getExecutorService();

        // Verify executor is not shutdown initially
        assertFalse(sharedExecutor.isShutdown(), "Executor should not be shutdown initially");

        var executionOp = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input-1\"")
                        .build())
                .build();

        var input1 = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "token1",
                new DurableExecutionInput.InitialExecutionState(List.of(executionOp), null));

        // Execute first handler
        var output1 = DurableExecutor.execute(
                input1,
                null,
                String.class,
                (userInput, ctx) -> ctx.step("test1", String.class, () -> "Result 1: " + userInput),
                config);

        assertEquals(ExecutionStatus.SUCCEEDED, output1.status());
        assertFalse(sharedExecutor.isShutdown(), "Executor should not be shutdown after first execution");

        // Create second input with different execution operation
        var executionOp2 = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input-2\"")
                        .build())
                .build();

        var input2 = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "token2",
                new DurableExecutionInput.InitialExecutionState(List.of(executionOp2), null));

        // Execute second handler using the same config (and thus same executor)
        var output2 = DurableExecutor.execute(
                input2,
                null,
                String.class,
                (userInput, ctx) -> ctx.step("test2", String.class, () -> "Result 2: " + userInput),
                config);

        assertEquals(ExecutionStatus.SUCCEEDED, output2.status());
        assertFalse(sharedExecutor.isShutdown(), "Executor should not be shutdown after second execution");

        // Verify both executions completed successfully and used the same executor
        assertTrue(output1.result().contains("Result 1: test-input-1"));
        assertTrue(output2.result().contains("Result 2: test-input-2"));
    }
}
