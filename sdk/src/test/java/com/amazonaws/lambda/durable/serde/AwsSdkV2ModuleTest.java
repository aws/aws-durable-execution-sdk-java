// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.amazonaws.lambda.durable.DurableHandler;
import com.amazonaws.lambda.durable.model.DurableExecutionInput;
import com.amazonaws.lambda.durable.model.DurableExecutionOutput;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;

class AwsSdkV2ModuleTest {

    @Test
    void testDurableExecutionInputDeserializationIncludingSdkV2Operation() throws Exception {
        ObjectMapper mapper = DurableHandler.createObjectMapper();

        String json = """
                {
                    "DurableExecutionArn": "c581e164-d7da-4108-8b35-109facaf1cc7",
                    "CheckpointToken": "eyJhcm4iOiJjNTgxZTE2NC1kN2RhLTQxMDgtOGIzNS0xMDlmYWNhZjFjYzciLCJzZXEiOjZ9",
                    "InitialExecutionState": {
                        "Operations": [
                            {
                                "Id": "bab0be0f-1c09-4a45-9a93-2e0579b9f81e",
                                "Type": "EXECUTION",
                                "Status": "STARTED",
                                "StartTimestamp": "2025-12-18 10:53:45.863776+00:00",
                                "ExecutionDetails": {
                                    "InputPayload": "{\\"name\\": \\"Alice\\"}"
                                }
                            },
                            {
                                "Id": "1",
                                "Type": "STEP",
                                "Status": "SUCCEEDED",
                                "Name": "async-operation",
                                "StartTimestamp": "2025-12-18 10:53:55.057877+00:00",
                                "EndTimestamp": "2025-12-18 10:53:57.413501+00:00",
                                "StepDetails": {
                                    "Attempt": 1,
                                    "Result": "\\"Processed: Alice\\""
                                }
                            },
                            {
                                "Id": "2",
                                "Type": "WAIT",
                                "Status": "SUCCEEDED",
                                "Name": "wait-3-seconds",
                                "StartTimestamp": "2025-12-18 10:53:55.374042+00:00",
                                "EndTimestamp": "2025-12-18 10:54:00.553627+00:00",
                                "WaitDetails": {
                                    "ScheduledEndTimestamp": "2025-12-18 10:53:58.374035+00:00"
                                }
                            }
                        ],
                        "NextMarker": ""
                    }
                }
                """;

        DurableExecutionInput input = mapper.readValue(json, DurableExecutionInput.class);

        // Verify top-level fields
        assertNotNull(input);
        assertEquals("c581e164-d7da-4108-8b35-109facaf1cc7", input.durableExecutionArn());
        assertEquals(
                "eyJhcm4iOiJjNTgxZTE2NC1kN2RhLTQxMDgtOGIzNS0xMDlmYWNhZjFjYzciLCJzZXEiOjZ9", input.checkpointToken());

        // Verify initial execution state
        assertNotNull(input.initialExecutionState());
        assertEquals("", input.initialExecutionState().nextMarker());

        // Verify operations list
        var operations = input.initialExecutionState().operations();
        assertNotNull(operations);
        assertEquals(3, operations.size());

        // Verify EXECUTION operation
        Operation executionOp = operations.get(0);
        assertEquals("bab0be0f-1c09-4a45-9a93-2e0579b9f81e", executionOp.id());
        assertEquals(OperationType.EXECUTION, executionOp.type());
        assertEquals(OperationStatus.STARTED, executionOp.status());
        assertNotNull(executionOp.startTimestamp());
        assertEquals(Instant.parse("2025-12-18T10:53:45.863776Z"), executionOp.startTimestamp());
        assertNotNull(executionOp.executionDetails());
        assertEquals("{\"name\": \"Alice\"}", executionOp.executionDetails().inputPayload());

        // Verify STEP operation
        Operation stepOp = operations.get(1);
        assertEquals("1", stepOp.id());
        assertEquals(OperationType.STEP, stepOp.type());
        assertEquals(OperationStatus.SUCCEEDED, stepOp.status());
        assertEquals("async-operation", stepOp.name());
        assertNotNull(stepOp.startTimestamp());
        assertNotNull(stepOp.endTimestamp());
        assertEquals(Instant.parse("2025-12-18T10:53:55.057877Z"), stepOp.startTimestamp());
        assertEquals(Instant.parse("2025-12-18T10:53:57.413501Z"), stepOp.endTimestamp());
        assertNotNull(stepOp.stepDetails());
        assertEquals(1, stepOp.stepDetails().attempt());
        assertEquals("\"Processed: Alice\"", stepOp.stepDetails().result());

        // Verify WAIT operation
        Operation waitOp = operations.get(2);
        assertEquals("2", waitOp.id());
        assertEquals(OperationType.WAIT, waitOp.type());
        assertEquals(OperationStatus.SUCCEEDED, waitOp.status());
        assertEquals("wait-3-seconds", waitOp.name());
        assertNotNull(waitOp.startTimestamp());
        assertNotNull(waitOp.endTimestamp());
        assertEquals(Instant.parse("2025-12-18T10:53:55.374042Z"), waitOp.startTimestamp());
        assertEquals(Instant.parse("2025-12-18T10:54:00.553627Z"), waitOp.endTimestamp());
        assertNotNull(waitOp.waitDetails());
        assertEquals(
                Instant.parse("2025-12-18T10:53:58.374035Z"),
                waitOp.waitDetails().scheduledEndTimestamp());
    }

    @Test
    void testErrorObjectSerializationAndDeserialization() throws Exception {
        ObjectMapper mapper = DurableHandler.createObjectMapper();

        // Create an ErrorObject using the builder
        var errorObject = ErrorObject.builder()
                .errorType("StepFailedException")
                .errorMessage("Step execution failed")
                .stackTrace(List.of(
                        "com.example.MyClass|myMethod|MyClass.java|123",
                        "com.example.OtherClass|otherMethod|OtherClass.java|456"))
                .build();

        var output = new DurableExecutionOutput(ExecutionStatus.FAILED, null, errorObject);

        // Serialize to JSON
        var json = mapper.writeValueAsString(output);

        // Verify serialization contains expected fields
        assertNotNull(json);

        // Deserialize back
        var deserialized = mapper.readValue(json, DurableExecutionOutput.class);

        // Verify deserialization
        assertNotNull(deserialized);
        assertEquals(ExecutionStatus.FAILED, deserialized.status());
        assertNotNull(deserialized.error());
        assertEquals("StepFailedException", deserialized.error().errorType());
        assertEquals("Step execution failed", deserialized.error().errorMessage());
        assertNotNull(deserialized.error().stackTrace());
        assertEquals(2, deserialized.error().stackTrace().size());
        assertEquals(
                "com.example.MyClass|myMethod|MyClass.java|123",
                deserialized.error().stackTrace().get(0));
        assertEquals(
                "com.example.OtherClass|otherMethod|OtherClass.java|456",
                deserialized.error().stackTrace().get(1));
    }

    @Test
    void testErrorObjectRoundTripWithNullFields() throws Exception {
        ObjectMapper mapper = DurableHandler.createObjectMapper();

        // Create an ErrorObject with minimal fields
        var errorObject = ErrorObject.builder()
                .errorType("CustomError")
                .errorMessage("Something went wrong")
                .build();

        var output = new DurableExecutionOutput(ExecutionStatus.FAILED, null, errorObject);

        // Serialize and deserialize
        var json = mapper.writeValueAsString(output);
        var deserialized = mapper.readValue(json, DurableExecutionOutput.class);

        // Verify
        assertNotNull(deserialized.error());
        assertEquals("CustomError", deserialized.error().errorType());
        assertEquals("Something went wrong", deserialized.error().errorMessage());
    }
}
