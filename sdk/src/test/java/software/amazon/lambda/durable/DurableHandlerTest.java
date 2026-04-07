// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class DurableHandlerTest {

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
                public Object handleRequest(Object input, DurableContext context) {
                    return null;
                }
            }
            new InvalidHandler();
            // Should not reach here
            throw new AssertionError("Expected IllegalArgumentException to be thrown");
        } catch (IllegalArgumentException e) {
            assertEquals(
                    "Cannot determine type from base class: class software.amazon.lambda.durable.DurableHandlerTest$1InvalidHandler",
                    e.getMessage());
        }
    }

    // Test handler implementation
    private static class TestDurableHandler extends DurableHandler<String, String> {
        @Override
        public String handleRequest(String input, DurableContext context) {
            return "processed: " + input;
        }
    }
}
