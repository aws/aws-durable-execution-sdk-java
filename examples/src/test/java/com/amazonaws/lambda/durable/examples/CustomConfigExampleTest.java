// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import org.junit.jupiter.api.Test;

class CustomConfigExampleTest {

    @Test
    void testCustomConfigExample() {
        var handler = new CustomConfigExample();

        // Create test runner using the handler function with the handler's configuration
        var runner = LocalDurableTestRunner.create(String.class, handler::handleRequest, handler.getConfiguration());

        // Run with input
        var result = runner.run("test-input");

        // Verify result
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // Get the operation result. This is the serialized result stored in the DAR backend
        var operation = result.getOperation("create-custom-object");
        var operationResult = operation.getStepDetails().result();

        // Assert that the stepDetails result uses snake_case (based on the custom serializer)
        assertTrue(operationResult.contains("user_id"), "Should contain snake_case 'user_id' key");
        assertTrue(operationResult.contains("full_name"), "Should contain snake_case 'full_name' key");
        assertTrue(operationResult.contains("user_age"), "Should contain snake_case 'user_age' key");
        assertTrue(operationResult.contains("email_address"), "Should contain snake_case 'email_address' key");

        // Verify that we got the expected output
        var output = result.getResult(String.class);
        assertNotNull(output);
        assertEquals("Created custom object: user123, John Doe, 25, john.doe@example.com", output);
    }
}
