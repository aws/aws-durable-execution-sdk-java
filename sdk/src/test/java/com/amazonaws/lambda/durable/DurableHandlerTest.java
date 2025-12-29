// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import com.amazonaws.lambda.durable.exception.StepFailedException;
import com.amazonaws.lambda.durable.model.DurableExecutionInput;
import com.amazonaws.lambda.durable.model.DurableExecutionOutput;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.lambda.model.ErrorObject;

class DurableHandlerTest {

    @Mock
    private Context lambdaContext;

    @Mock
    private DurableExecutionClient mockClient;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = DurableHandler.createObjectMapper();
    }

    @Test
    void testObjectMapperDeserializesDurableExecutionInput() throws IOException {
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

        var input = objectMapper.readValue(json, DurableExecutionInput.class);

        assertNotNull(input);
        assertEquals("arn:aws:lambda:us-east-1:123456789012:function:my-function", input.durableExecutionArn());
        assertEquals("token-123", input.checkpointToken());
        assertNotNull(input.initialExecutionState());
    }

    @Test
    void testObjectMapperSerializesSuccessOutput() throws IOException {
        var output = DurableExecutionOutput.success("test-result");

        var json = objectMapper.writeValueAsString(output);

        assertTrue(json.contains("\"Status\":\"SUCCEEDED\""));
        assertTrue(json.contains("\"Result\":\"test-result\""));
        assertTrue(json.contains("\"Error\":null"));
    }

    @Test
    void testObjectMapperSerializesPendingOutput() throws IOException {
        var output = DurableExecutionOutput.pending();

        var json = objectMapper.writeValueAsString(output);

        assertTrue(json.contains("\"Status\":\"PENDING\""));
    }

    @Test
    void testObjectMapperSerializesFailureOutputWithErrorObject() throws IOException {
        var exception = new StepFailedException("Step execution failed", new RuntimeException("Root cause"));
        var output = DurableExecutionOutput.failure(exception);

        var json = objectMapper.writeValueAsString(output);

        assertTrue(json.contains("\"Status\":\"FAILED\""));
        assertTrue(json.contains("\"ErrorType\":\"StepFailedException\""));
        assertTrue(json.contains("\"ErrorMessage\":\"Step execution failed\""));
        assertTrue(json.contains("\"StackTrace\":["));
    }

    @Test
    void testObjectMapperHandlesErrorObjectFromAwsSdk() throws IOException {
        var errorObject = ErrorObject.builder()
                .errorType("CustomError")
                .errorMessage("Something went wrong")
                .stackTrace(List.of("line1|method1|file1.java|10", "line2|method2|file2.java|20"))
                .build();

        var output = new DurableExecutionOutput(ExecutionStatus.FAILED, null, errorObject);
        var json = objectMapper.writeValueAsString(output);

        // Verify serialization with custom ErrorObjectSerializer
        assertTrue(json.contains("\"ErrorType\":\"CustomError\""));
        assertTrue(json.contains("\"ErrorMessage\":\"Something went wrong\""));
        assertTrue(json.contains("\"StackTrace\":["));
        assertTrue(json.contains("\"Status\":\"FAILED\""));

        // Verify deserialization round-trip
        var deserialized = objectMapper.readValue(json, DurableExecutionOutput.class);
        assertEquals(ExecutionStatus.FAILED, deserialized.status());
        assertNotNull(deserialized.error());
        assertEquals("CustomError", deserialized.error().errorType());
        assertEquals("Something went wrong", deserialized.error().errorMessage());
        assertEquals(2, deserialized.error().stackTrace().size());
    }

    @Test
    void testHandlerExtractsInputTypeFromGenerics() {
        // This test verifies that the handler successfully extracts the input type
        // (String)
        // from the generic superclass. If type extraction fails, the constructor throws
        // IllegalArgumentException with message "Cannot determine input type parameter"
        var handler = new TestDurableHandler();

        // Verify handler was created successfully
        assertNotNull(handler);

        // Verify the handler can process input (which requires correct type extraction)
        var result = handler.handleRequest("test-input", null);
        assertEquals("processed: test-input", result);
    }

    @Test
    void testHandlerWithoutGenericsThrowsException() {
        // Verify that a handler without proper generic type information throws an
        // exception
        try {
            @SuppressWarnings("rawtypes")
            class InvalidHandler extends DurableHandler {
                @Override
                protected Object handleRequest(Object input, DurableContext context) {
                    return null;
                }
            }
            new InvalidHandler();
            // Should not reach here
            throw new AssertionError("Expected IllegalArgumentException to be thrown");
        } catch (IllegalArgumentException e) {
            assertEquals("Cannot determine input type parameter", e.getMessage());
        }
    }

    @Test
    void testObjectMapperIgnoresUnknownProperties() throws IOException {
        var json = """
                {
                    "Status": "SUCCEEDED",
                    "Result": "test",
                    "Error": null,
                    "UnknownProperty": "should be ignored"
                }
                """;

        // Should not fail on unknown properties
        var output = objectMapper.readValue(json, DurableExecutionOutput.class);

        assertNotNull(output);
        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals("test", output.result());
    }

    // Test handler implementation
    private static class TestDurableHandler extends DurableHandler<String, String> {
        public TestDurableHandler() {
            super();
        }

        @Override
        protected String handleRequest(String input, DurableContext context) {
            return "processed: " + input;
        }
    }
}
