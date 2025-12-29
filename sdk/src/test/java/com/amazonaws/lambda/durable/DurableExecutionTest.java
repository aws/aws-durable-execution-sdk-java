// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.model.DurableExecutionInput;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.testing.LocalMemoryExecutionClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.*;

class DurableExecutionTest {

    @Test
    void testExecuteSuccess() {
        var client = new LocalMemoryExecutionClient();
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
                new com.amazonaws.lambda.durable.model.DurableExecutionInput.InitialExecutionState(
                        List.of(executionOp), null));

        var output = DurableExecutor.execute(
                input,
                null,
                String.class,
                (userInput, ctx) -> {
                    var result = ctx.step("test", String.class, () -> "Hello " + userInput);
                    return result;
                },
                client);

        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertNotNull(output.result());
        assertTrue(output.result().contains("Hello test-input"));
    }

    @Test
    void testExecutePending() {
        var client = new LocalMemoryExecutionClient();
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
                new com.amazonaws.lambda.durable.model.DurableExecutionInput.InitialExecutionState(
                        List.of(executionOp), null));

        var output = DurableExecutor.execute(
                input,
                null,
                String.class,
                (userInput, ctx) -> {
                    ctx.step("step1", String.class, () -> "Done");
                    ctx.wait(java.time.Duration.ofSeconds(60));
                    return "Should not reach here";
                },
                client);

        assertEquals(ExecutionStatus.PENDING, output.status());
        assertNull(output.result());
    }

    @Test
    void testExecuteFailure() {
        var client = new LocalMemoryExecutionClient();
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
                new com.amazonaws.lambda.durable.model.DurableExecutionInput.InitialExecutionState(
                        List.of(executionOp), null));

        var output = DurableExecutor.execute(
                input,
                null,
                String.class,
                (userInput, ctx) -> {
                    throw new RuntimeException("Test error");
                },
                client);

        assertEquals(ExecutionStatus.FAILED, output.status());
        assertNotNull(output.error());
        assertEquals("RuntimeException", output.error().errorType());
        assertEquals("Test error", output.error().errorMessage());
    }

    @Test
    void testExecuteReplay() {
        var client = new LocalMemoryExecutionClient();
        var executionOp = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(ExecutionDetails.builder()
                        .inputPayload("\"test-input\"")
                        .build())
                .build();

        var input1 = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "token1",
                new com.amazonaws.lambda.durable.model.DurableExecutionInput.InitialExecutionState(
                        List.of(executionOp), null));

        var output1 = DurableExecutor.execute(
                input1,
                null,
                String.class,
                (userInput, ctx) -> {
                    var result = ctx.step("step1", String.class, () -> "First");
                    return result;
                },
                client);

        assertEquals(ExecutionStatus.SUCCEEDED, output1.status());

        // Second execution with replay
        var completedStep = Operation.builder()
                .id("1")
                .name("step1")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"First\"").build())
                .build();

        var input2 = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "token2",
                new com.amazonaws.lambda.durable.model.DurableExecutionInput.InitialExecutionState(
                        List.of(executionOp, completedStep), null));

        var output2 = DurableExecutor.execute(
                input2,
                null,
                String.class,
                (userInput, ctx) -> {
                    var result = ctx.step("step1", String.class, () -> "Second");
                    return result;
                },
                client);

        assertEquals(ExecutionStatus.SUCCEEDED, output2.status());
        assertTrue(output2.result().contains("First"));
    }

    @Test
    void testValidationNoOperations() {
        var client = new LocalMemoryExecutionClient();
        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "token1",
                new com.amazonaws.lambda.durable.model.DurableExecutionInput.InitialExecutionState(List.of(), null));

        var exception = assertThrows(
                IllegalStateException.class,
                () -> DurableExecutor.execute(input, null, String.class, (userInput, ctx) -> "result", client));

        assertEquals("First operation must be EXECUTION", exception.getMessage());
    }

    @Test
    void testValidationWrongFirstOperation() {
        var client = new LocalMemoryExecutionClient();
        var stepOp = Operation.builder()
                .id("1")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"result\"").build())
                .build();

        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "token1",
                new com.amazonaws.lambda.durable.model.DurableExecutionInput.InitialExecutionState(
                        List.of(stepOp), null));

        var exception = assertThrows(
                IllegalStateException.class,
                () -> DurableExecutor.execute(input, null, String.class, (userInput, ctx) -> "result", client));

        assertEquals("First operation must be EXECUTION", exception.getMessage());
    }

    @Test
    void testValidationMissingExecutionDetails() {
        var client = new LocalMemoryExecutionClient();
        var executionOp = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .build();

        var input = new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "token1",
                new com.amazonaws.lambda.durable.model.DurableExecutionInput.InitialExecutionState(
                        List.of(executionOp), null));

        var exception = assertThrows(
                IllegalStateException.class,
                () -> DurableExecutor.execute(input, null, String.class, (userInput, ctx) -> "result", client));

        assertEquals("EXECUTION operation missing executionDetails", exception.getMessage());
    }

    @Test
    void testLargePayloadCheckpointing() {
        var client = new LocalMemoryExecutionClient();
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

        // Create a large result that exceeds 6MB limit
        var largeString = "x".repeat(7 * 1024 * 1024); // 7MB string

        var output = DurableExecutor.execute(input, null, String.class, (userInput, ctx) -> largeString, client);

        // Should succeed but return empty result since payload was checkpointed
        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals("", output.result());

        // Verify that the checkpoint was sent with the large payload
        var updates = client.getOperationUpdates();
        assertFalse(updates.isEmpty(), "Expected checkpoint updates but got none");
        var lastUpdate = updates.get(updates.size() - 1);
        assertEquals(OperationType.EXECUTION, lastUpdate.type());
        assertEquals(OperationAction.SUCCEED, lastUpdate.action());
        assertNotNull(lastUpdate.payload());
        assertTrue(lastUpdate.payload().length() > 6 * 1024 * 1024);
    }

    @Test
    void testSmallPayloadNoExtraCheckpoint() {
        var client = new LocalMemoryExecutionClient();
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

        var smallResult = "Small result";

        var output = DurableExecutor.execute(input, null, String.class, (userInput, ctx) -> smallResult, client);

        // Should succeed and return the result directly (no extra checkpoint)
        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertNotNull(output.result());
        assertTrue(output.result().contains(smallResult));

        // Verify no EXECUTION checkpoint was sent (backend will auto-checkpoint)
        var updates = client.getOperationUpdates();
        var executionUpdates = updates.stream()
                .filter(u -> u.type() == OperationType.EXECUTION)
                .toList();
        assertTrue(executionUpdates.isEmpty(), "No explicit EXECUTION checkpoint should be sent for small payloads");
    }
}
