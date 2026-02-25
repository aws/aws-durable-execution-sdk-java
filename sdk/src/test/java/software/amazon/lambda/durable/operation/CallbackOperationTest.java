// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.CallbackConfig;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.CallbackTimeoutException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class CallbackOperationTest {

    private static final String OPERATION_ID = "1";
    private static final String EXECUTION_OPERATION_ID = "0";
    private static final String OPERATION_NAME = "approval";

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
        var operations = new ArrayList<Operation>();
        operations.add(Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .build());
        operations.addAll(initialOperations);
        var initialState =
                CheckpointUpdatedExecutionState.builder().operations(operations).build();
        var executionManager = new ExecutionManager(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "test-token",
                initialState,
                DurableConfig.builder().withDurableExecutionClient(client).build());
        executionManager.setCurrentThreadContext(new ThreadContext("Root", ThreadType.CONTEXT));
        return executionManager;
    }

    @Test
    void executeCreatesCheckpointAndGetsCallbackId() {
        var executionManager = createExecutionManager(List.of());
        var serDes = new JacksonSerDes();

        var operation = new CallbackOperation<>(
                OPERATION_ID,
                OPERATION_NAME,
                TypeToken.get(String.class),
                CallbackConfig.builder().serDes(serDes).build(),
                executionManager);
        operation.execute();

        assertNotNull(operation.callbackId());
    }

    @Test
    void executeWithConfigSetsOptions() {
        var executionManager = createExecutionManager(List.of());
        var serDes = new JacksonSerDes();
        var config = CallbackConfig.builder()
                .timeout(Duration.ofMinutes(5))
                .heartbeatTimeout(Duration.ofSeconds(30))
                .serDes(serDes)
                .build();

        var operation = new CallbackOperation<>(
                OPERATION_ID, OPERATION_NAME, TypeToken.get(String.class), config, executionManager);
        operation.execute();

        assertNotNull(operation.callbackId());
    }

    @Test
    void replayReturnsExistingCallbackIdWhenSucceeded() {
        var existingCallback = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.CALLBACK)
                .status(OperationStatus.SUCCEEDED)
                .callbackDetails(CallbackDetails.builder()
                        .callbackId("existing-callback-id")
                        .result("\"approved\"")
                        .build())
                .build();
        var executionManager = createExecutionManager(List.of(existingCallback));
        var serDes = new JacksonSerDes();

        var operation = new CallbackOperation<>(
                OPERATION_ID,
                OPERATION_NAME,
                TypeToken.get(String.class),
                CallbackConfig.builder().serDes(serDes).build(),
                executionManager);
        operation.execute();

        assertEquals("existing-callback-id", operation.callbackId());
    }

    @Test
    void getReturnsDeserializedResultWhenSucceeded() {
        var existingCallback = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.CALLBACK)
                .status(OperationStatus.SUCCEEDED)
                .callbackDetails(CallbackDetails.builder()
                        .callbackId("callback-id")
                        .result("\"approved\"")
                        .build())
                .build();
        var executionManager = createExecutionManager(List.of(existingCallback));
        var serDes = new JacksonSerDes();

        var operation = new CallbackOperation<>(
                OPERATION_ID,
                OPERATION_NAME,
                TypeToken.get(String.class),
                CallbackConfig.builder().serDes(serDes).build(),
                executionManager);
        operation.execute();
        var result = operation.get();

        assertEquals("approved", result);
    }

    @Test
    void getThrowsCallbackExceptionWhenFailed() {
        var existingCallback = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
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

        var operation = new CallbackOperation<>(
                OPERATION_ID,
                OPERATION_NAME,
                TypeToken.get(String.class),
                CallbackConfig.builder().serDes(serDes).build(),
                executionManager);
        operation.execute();

        var exception = assertThrows(CallbackFailedException.class, operation::get);
        assertTrue(exception.getMessage().contains("ValidationError"));
    }

    @Test
    void getThrowsCallbackTimeoutExceptionWhenTimedOut() {
        var existingCallback = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.CALLBACK)
                .status(OperationStatus.TIMED_OUT)
                .callbackDetails(
                        CallbackDetails.builder().callbackId("callback-id").build())
                .build();
        var executionManager = createExecutionManager(List.of(existingCallback));
        var serDes = new JacksonSerDes();

        var operation = new CallbackOperation<>(
                OPERATION_ID,
                OPERATION_NAME,
                TypeToken.get(String.class),
                CallbackConfig.builder().serDes(serDes).build(),
                executionManager);
        operation.execute();

        var exception = assertThrows(CallbackTimeoutException.class, operation::get);
        assertTrue(exception.getMessage().contains("callback-id"));
    }

    @Test
    void operationUsesCustomSerDesWhenConfigContainsOne() {
        var customSerDes = new TrackingSerDes();

        var existingCallback = Operation.builder()
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
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
                OPERATION_ID, OPERATION_NAME, TypeToken.get(String.class), config, executionManager);
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
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.CALLBACK)
                .status(OperationStatus.SUCCEEDED)
                .callbackDetails(CallbackDetails.builder()
                        .callbackId("callback-id")
                        .result("\"approved\"")
                        .build())
                .build();
        var executionManager = createExecutionManager(List.of(existingCallback));

        var operation = new CallbackOperation<>(
                OPERATION_ID,
                OPERATION_NAME,
                TypeToken.get(String.class),
                CallbackConfig.builder().serDes(customSerDes).build(),
                executionManager);
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
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
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
                OPERATION_ID, OPERATION_NAME, TypeToken.get(String.class), config, executionManager);
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
                .id(OPERATION_ID)
                .name(OPERATION_NAME)
                .type(OperationType.CALLBACK)
                .status(OperationStatus.SUCCEEDED)
                .callbackDetails(CallbackDetails.builder()
                        .callbackId("test-callback-123")
                        .result("data")
                        .build())
                .build();
        var executionManager = createExecutionManager(List.of(existingCallback));

        var operation = new CallbackOperation<>(
                OPERATION_ID,
                OPERATION_NAME,
                TypeToken.get(String.class),
                CallbackConfig.builder().serDes(failingSerDes).build(),
                executionManager);
        operation.execute();

        var exception = assertThrows(SerDesException.class, operation::get);
        assertEquals("Invalid base64 encoding", exception.getMessage());
    }
}
