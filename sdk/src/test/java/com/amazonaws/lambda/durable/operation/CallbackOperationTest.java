// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.CallbackConfig;
import com.amazonaws.lambda.durable.TestUtils;
import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.exception.CallbackFailedException;
import com.amazonaws.lambda.durable.exception.CallbackTimeoutException;
import com.amazonaws.lambda.durable.exception.SerDesException;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.model.DurableExecutionInput.InitialExecutionState;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.*;

class CallbackOperationTest {

    /** Custom SerDes that tracks deserialization calls. */
    static class TrackingSerDes implements SerDes {
        private final JacksonSerDes delegate = new JacksonSerDes();
        private final AtomicInteger deserializeCount = new AtomicInteger(0);

        @Override
        public String serialize(Object value) {
            return delegate.serialize(value);
        }

        @Override
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            deserializeCount.incrementAndGet();
            return delegate.deserialize(data, typeToken);
        }

        public int getDeserializeCount() {
            return deserializeCount.get();
        }
    }

    /** Custom SerDes that always throws SerDesException. */
    static class FailingSerDes implements SerDes {
        @Override
        public String serialize(Object value) {
            throw new SerDesException("Serialization failed");
        }

        @Override
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            throw new SerDesException("Invalid base64 encoding");
        }
    }

    private ExecutionManager createExecutionManager(List<Operation> initialOperations) {
        var client = TestUtils.createMockClient();
        var initialState = new InitialExecutionState(initialOperations, null);
        return new ExecutionManager(
                "arn:aws:lambda:us-east-1:123456789012:function:test", "test-token", initialState, client);
    }

    @Test
    void executeCreatesCheckpointAndGetsCallbackId() {
        var executionOp = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .build();
        var executionManager = createExecutionManager(List.of(executionOp));
        var serDes = new JacksonSerDes();

        var operation =
                new CallbackOperation<>("1", "approval", TypeToken.get(String.class), null, executionManager, serDes);
        operation.execute();

        assertNotNull(operation.getCallbackId());
    }

    @Test
    void executeWithConfigSetsOptions() {
        var executionOp = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .build();
        var executionManager = createExecutionManager(List.of(executionOp));
        var serDes = new JacksonSerDes();
        var config = CallbackConfig.builder()
                .timeout(Duration.ofMinutes(5))
                .heartbeatTimeout(Duration.ofSeconds(30))
                .build();

        var operation =
                new CallbackOperation<>("1", "approval", TypeToken.get(String.class), config, executionManager, serDes);
        operation.execute();

        assertNotNull(operation.getCallbackId());
    }

    @Test
    void replayReturnsExistingCallbackIdWhenSucceeded() {
        var existingCallback = Operation.builder()
                .id("1")
                .name("approval")
                .type(OperationType.CALLBACK)
                .status(OperationStatus.SUCCEEDED)
                .callbackDetails(CallbackDetails.builder()
                        .callbackId("existing-callback-id")
                        .result("\"approved\"")
                        .build())
                .build();
        var executionManager = createExecutionManager(List.of(existingCallback));
        var serDes = new JacksonSerDes();

        var operation =
                new CallbackOperation<>("1", "approval", TypeToken.get(String.class), null, executionManager, serDes);
        operation.execute();

        assertEquals("existing-callback-id", operation.getCallbackId());
    }

    @Test
    void getReturnsDeserializedResultWhenSucceeded() {
        var existingCallback = Operation.builder()
                .id("1")
                .name("approval")
                .type(OperationType.CALLBACK)
                .status(OperationStatus.SUCCEEDED)
                .callbackDetails(CallbackDetails.builder()
                        .callbackId("callback-id")
                        .result("\"approved\"")
                        .build())
                .build();
        var executionManager = createExecutionManager(List.of(existingCallback));
        var serDes = new JacksonSerDes();

        var operation =
                new CallbackOperation<>("1", "approval", TypeToken.get(String.class), null, executionManager, serDes);
        operation.execute();
        var result = operation.get();

        assertEquals("approved", result);
    }

    @Test
    void getThrowsCallbackExceptionWhenFailed() {
        var existingCallback = Operation.builder()
                .id("1")
                .name("approval")
                .type(OperationType.CALLBACK)
                .status(OperationStatus.FAILED)
                .callbackDetails(CallbackDetails.builder()
                        .callbackId("callback-id")
                        .error(ErrorObject.builder()
                                .errorType("ValidationError")
                                .errorMessage("Invalid input")
                                .build())
                        .build())
                .build();
        var executionManager = createExecutionManager(List.of(existingCallback));
        var serDes = new JacksonSerDes();

        var operation =
                new CallbackOperation<>("1", "approval", TypeToken.get(String.class), null, executionManager, serDes);
        operation.execute();

        var exception = assertThrows(CallbackFailedException.class, operation::get);
        assertTrue(exception.getMessage().contains("ValidationError"));
    }

    @Test
    void getThrowsCallbackTimeoutExceptionWhenTimedOut() {
        var existingCallback = Operation.builder()
                .id("1")
                .name("approval")
                .type(OperationType.CALLBACK)
                .status(OperationStatus.TIMED_OUT)
                .callbackDetails(
                        CallbackDetails.builder().callbackId("callback-id").build())
                .build();
        var executionManager = createExecutionManager(List.of(existingCallback));
        var serDes = new JacksonSerDes();

        var operation =
                new CallbackOperation<>("1", "approval", TypeToken.get(String.class), null, executionManager, serDes);
        operation.execute();

        var exception = assertThrows(CallbackTimeoutException.class, operation::get);
        assertTrue(exception.getMessage().contains("callback-id"));
    }

    @Test
    void operationUsesCustomSerDesWhenConfigContainsOne() {
        var customSerDes = new TrackingSerDes();
        var defaultSerDes = new JacksonSerDes();

        var existingCallback = Operation.builder()
                .id("1")
                .name("approval")
                .type(OperationType.CALLBACK)
                .status(OperationStatus.SUCCEEDED)
                .callbackDetails(CallbackDetails.builder()
                        .callbackId("callback-id")
                        .result("\"approved\"")
                        .build())
                .build();
        var executionManager = createExecutionManager(List.of(existingCallback));

        var config = CallbackConfig.builder().serDes(customSerDes).build();
        var operation = new CallbackOperation<>(
                "1", "approval", TypeToken.get(String.class), config, executionManager, defaultSerDes);
        operation.execute();
        var result = operation.get();

        assertEquals("approved", result);
        // Custom SerDes should have been used for deserialization
        assertEquals(1, customSerDes.getDeserializeCount(), "Custom SerDes should have been used");
    }

    @Test
    void operationUsesDefaultSerDesWhenConfigIsNull() {
        var customSerDes = new TrackingSerDes();

        var existingCallback = Operation.builder()
                .id("1")
                .name("approval")
                .type(OperationType.CALLBACK)
                .status(OperationStatus.SUCCEEDED)
                .callbackDetails(CallbackDetails.builder()
                        .callbackId("callback-id")
                        .result("\"approved\"")
                        .build())
                .build();
        var executionManager = createExecutionManager(List.of(existingCallback));

        var operation = new CallbackOperation<>(
                "1", "approval", TypeToken.get(String.class), null, executionManager, customSerDes);
        operation.execute();
        var result = operation.get();

        assertEquals("approved", result);
        // Custom SerDes (passed as default) should have been used
        assertEquals(1, customSerDes.getDeserializeCount(), "Default SerDes should have been used");
    }

    @Test
    void operationUsesDefaultSerDesWhenConfigSerDesIsNull() {
        var customSerDes = new TrackingSerDes();

        var existingCallback = Operation.builder()
                .id("1")
                .name("approval")
                .type(OperationType.CALLBACK)
                .status(OperationStatus.SUCCEEDED)
                .callbackDetails(CallbackDetails.builder()
                        .callbackId("callback-id")
                        .result("\"approved\"")
                        .build())
                .build();
        var executionManager = createExecutionManager(List.of(existingCallback));

        var config = CallbackConfig.builder().serDes(null).build();
        var operation = new CallbackOperation<>(
                "1", "approval", TypeToken.get(String.class), config, executionManager, customSerDes);
        operation.execute();
        var result = operation.get();

        assertEquals("approved", result);
        // Custom SerDes (passed as default) should have been used
        assertEquals(1, customSerDes.getDeserializeCount(), "Default SerDes should have been used");
    }

    @Test
    void getThrowsSerDesExceptionWithHelpfulMessageWhenDeserializationFails() {
        var failingSerDes = new FailingSerDes();

        var existingCallback = Operation.builder()
                .id("1")
                .name("approval")
                .type(OperationType.CALLBACK)
                .status(OperationStatus.SUCCEEDED)
                .callbackDetails(CallbackDetails.builder()
                        .callbackId("test-callback-123")
                        .result("invalid-data")
                        .build())
                .build();
        var executionManager = createExecutionManager(List.of(existingCallback));

        var operation = new CallbackOperation<>(
                "1", "approval", TypeToken.get(String.class), null, executionManager, failingSerDes);
        operation.execute();

        var exception = assertThrows(SerDesException.class, operation::get);
        assertEquals("Invalid base64 encoding", exception.getMessage());
    }
}
