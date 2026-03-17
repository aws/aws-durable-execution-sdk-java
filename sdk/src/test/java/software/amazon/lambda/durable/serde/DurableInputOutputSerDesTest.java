// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;

class DurableInputOutputSerDesTest {

    DurableInputOutputSerDes serDes;

    @BeforeEach
    void setUp() {
        serDes = new DurableInputOutputSerDes();
    }

    @Test
    void testObjectMapperSerializesPendingOutput() {
        var output = DurableExecutionOutput.pending();

        var json = serDes.serialize(output);

        assertTrue(json.contains("\"Status\":\"PENDING\""));
    }

    @Test
    void testObjectMapperDeserializesDurableExecutionInput() {
        var json = """
                {
                    "DurableExecutionArn": "arn:aws:lambda:us-east-1:123456789012:function:my-function",
                    "CheckpointToken": "token-123",
                    "InitialExecutionState": {
                        "Operations": [],
                        "NextMarker": null
                    }
                }
                """;

        var input = serDes.deserialize(json, TypeToken.get(DurableExecutionInput.class));

        assertNotNull(input);
        assertEquals("arn:aws:lambda:us-east-1:123456789012:function:my-function", input.durableExecutionArn());
        assertEquals("token-123", input.checkpointToken());
        assertNotNull(input.initialExecutionState());
    }

    @Test
    void testObjectMapperSerializesSuccessOutput() {
        var output = DurableExecutionOutput.success("test-result");

        var json = serDes.serialize(output);

        assertTrue(json.contains("\"Status\":\"SUCCEEDED\""));
        assertTrue(json.contains("\"Result\":\"test-result\""));
        assertTrue(json.contains("\"Error\":null"));
    }

    @Test
    void testObjectMapperSerializesFailureOutputWithErrorObject() {
        var output = DurableExecutionOutput.failure(ErrorObject.builder()
                .errorType("myErrorType")
                .errorMessage("myErrorMessage")
                .errorData("myErrorData")
                .stackTrace(List.of("s1", "s2"))
                .build());

        var json = serDes.serialize(output);

        assertTrue(json.contains("\"Status\":\"FAILED\""));
        assertTrue(json.contains("\"ErrorType\":\"myErrorType\""));
        assertTrue(json.contains("\"ErrorMessage\":\"myErrorMessage\""));
        assertTrue(json.contains("\"StackTrace\":["));
        assertTrue(json.contains("\"ErrorData\":\"myErrorData\""));
    }

    @Test
    void testObjectMapperHandlesErrorObjectFromAwsSdk() {
        var errorObject = ErrorObject.builder()
                .errorType("CustomError")
                .errorMessage("Something went wrong")
                .stackTrace(List.of("line1|method1|file1.java|10", "line2|method2|file2.java|20"))
                .build();

        var output = new DurableExecutionOutput(ExecutionStatus.FAILED, null, errorObject);
        var json = serDes.serialize(output);

        // Verify serialization with custom ErrorObjectSerializer
        assertTrue(json.contains("\"ErrorType\":\"CustomError\""));
        assertTrue(json.contains("\"ErrorMessage\":\"Something went wrong\""));
        assertTrue(json.contains("\"StackTrace\":["));
        assertTrue(json.contains("\"Status\":\"FAILED\""));

        // Verify deserialization round-trip
        var deserialized = serDes.deserialize(json, TypeToken.get(DurableExecutionOutput.class));
        assertEquals(ExecutionStatus.FAILED, deserialized.status());
        assertNotNull(deserialized.error());
        assertEquals("CustomError", deserialized.error().errorType());
        assertEquals("Something went wrong", deserialized.error().errorMessage());
        assertEquals(2, deserialized.error().stackTrace().size());
    }

    @Test
    void testObjectMapperIgnoresUnknownProperties() {
        var json = """
                {
                    "Status": "SUCCEEDED",
                    "Result": "test",
                    "Error": null,
                    "UnknownProperty": "should be ignored"
                }
                """;

        // Should not fail on unknown properties
        var output = serDes.deserialize(json, TypeToken.get(DurableExecutionOutput.class));

        assertNotNull(output);
        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals("test", output.result());
    }
}
