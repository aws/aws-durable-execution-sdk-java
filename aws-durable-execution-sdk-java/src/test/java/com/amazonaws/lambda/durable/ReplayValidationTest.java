// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.amazonaws.lambda.durable.exception.NonDeterministicExecutionException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.logging.LoggerConfig;
import com.amazonaws.lambda.durable.model.DurableExecutionInput.InitialExecutionState;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.*;

class ReplayValidationTest {

    private DurableContext createTestContext(List<Operation> initialOperations) {
        var client = TestUtils.createMockClient();
        var executor = Executors.newCachedThreadPool();
        var executionOp = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .build();
        var operations = Stream.concat(Stream.of(executionOp), initialOperations.stream())
                .toList();
        var initialExecutionState = new InitialExecutionState(operations, null);
        var executionManager = new ExecutionManager(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "test-token",
                initialExecutionState,
                client,
                executor);
        var serDes = new JacksonSerDes();
        return new DurableContext(executionManager, serDes, null, LoggerConfig.defaults());
    }

    @Test
    void shouldPassValidationWhenNoCheckpointExists() {
        // Given: No existing operation
        var context = createTestContext(List.of());

        // When & Then: Should not throw
        assertDoesNotThrow(() -> context.step("test", String.class, () -> "result"));
    }

    @Test
    void shouldPassValidationWhenStepTypeAndNameMatch() {
        // Given: Existing STEP operation with matching name
        var existingOp = Operation.builder()
                .id("1")
                .name("test")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"result\"").build())
                .build();

        var context = createTestContext(List.of(existingOp));

        // When & Then: Should not throw
        assertDoesNotThrow(() -> context.step("test", String.class, () -> "result"));
    }

    @Test
    void shouldPassValidationWhenWaitTypeMatches() {
        // Given: Existing WAIT operation
        var existingOp = Operation.builder()
                .id("1")
                .type(OperationType.WAIT)
                .status(OperationStatus.SUCCEEDED)
                .build();

        var context = createTestContext(List.of(existingOp));

        // When & Then: Should not throw
        assertDoesNotThrow(() -> context.wait(Duration.ofSeconds(1)));
    }

    @Test
    void shouldThrowWhenOperationTypeMismatches() {
        // Given: Existing WAIT operation but current is STEP
        var existingOp = Operation.builder()
                .id("1")
                .name("test")
                .type(OperationType.WAIT)
                .status(OperationStatus.SUCCEEDED)
                .build();

        var context = createTestContext(List.of(existingOp));

        // When & Then: Should throw NonDeterministicExecutionException
        var exception = assertThrows(
                NonDeterministicExecutionException.class, () -> context.step("test", String.class, () -> "result"));

        assertTrue(exception.getMessage().contains("Operation type mismatch"));
        assertTrue(exception.getMessage().contains("Expected WAIT"));
        assertTrue(exception.getMessage().contains("got STEP"));
    }

    @Test
    void shouldThrowWhenOperationNameMismatches() {
        // Given: Existing STEP operation with different name
        var existingOp = Operation.builder()
                .id("1")
                .name("original")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"result\"").build())
                .build();

        var context = createTestContext(List.of(existingOp));

        // When & Then: Should throw NonDeterministicExecutionException
        var exception = assertThrows(
                NonDeterministicExecutionException.class, () -> context.step("changed", String.class, () -> "result"));

        assertTrue(exception.getMessage().contains("Operation name mismatch"));
        assertTrue(exception.getMessage().contains("Expected \"original\""));
        assertTrue(exception.getMessage().contains("got \"changed\""));
    }

    @Test
    void shouldHandleNullNamesCorrectly() {
        // Given: Existing STEP operation with null name
        var existingOp = Operation.builder()
                .id("1")
                .name(null)
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"result\"").build())
                .build();

        var context = createTestContext(List.of(existingOp));

        // When & Then: Should not throw when both names are null
        assertDoesNotThrow(() -> context.step(null, String.class, () -> "result"));
    }

    @Test
    void shouldThrowWhenNameChangesFromNullToValue() {
        // Given: Existing STEP operation with null name
        var existingOp = Operation.builder()
                .id("1")
                .name(null)
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"result\"").build())
                .build();

        var context = createTestContext(List.of(existingOp));

        // When & Then: Should throw when name changes from null to value
        var exception = assertThrows(
                NonDeterministicExecutionException.class, () -> context.step("newName", String.class, () -> "result"));

        assertTrue(exception.getMessage().contains("Operation name mismatch"));
        assertTrue(exception.getMessage().contains("Expected \"null\""));
        assertTrue(exception.getMessage().contains("got \"newName\""));
    }

    @Test
    void shouldThrowWhenNameChangesFromValueToNull() {
        // Given: Existing STEP operation with a name
        var existingOp = Operation.builder()
                .id("1")
                .name("existingName")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"result\"").build())
                .build();

        var context = createTestContext(List.of(existingOp));

        // When & Then: Should throw when name changes from value to null
        var exception = assertThrows(
                NonDeterministicExecutionException.class, () -> context.step(null, String.class, () -> "result"));

        assertTrue(exception.getMessage().contains("Operation name mismatch"));
        assertTrue(exception.getMessage().contains("Expected \"existingName\""));
        assertTrue(exception.getMessage().contains("got \"null\""));
    }

    @Test
    void shouldValidateStepAsyncOperations() {
        // Given: Existing WAIT operation but current is STEP (async)
        var existingOp = Operation.builder()
                .id("1")
                .name("test")
                .type(OperationType.WAIT)
                .status(OperationStatus.SUCCEEDED)
                .build();

        var context = createTestContext(List.of(existingOp));

        // When & Then: Should throw NonDeterministicExecutionException
        var exception = assertThrows(
                NonDeterministicExecutionException.class,
                () -> context.stepAsync("test", String.class, () -> "result"));

        assertTrue(exception.getMessage().contains("Operation type mismatch"));
        assertTrue(exception.getMessage().contains("Expected WAIT"));
        assertTrue(exception.getMessage().contains("got STEP"));
    }

    @Test
    void shouldSkipValidationWhenOperationTypeIsNull() {
        // Given: Existing operation with null type (edge case)
        var existingOp = Operation.builder()
                .id("1")
                .name("test")
                .type((OperationType) null)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("\"result\"").build())
                .build();

        var context = createTestContext(List.of(existingOp));

        // When & Then: Should not throw (validation skipped)
        assertDoesNotThrow(() -> context.step("test", String.class, () -> "result"));
    }
}
