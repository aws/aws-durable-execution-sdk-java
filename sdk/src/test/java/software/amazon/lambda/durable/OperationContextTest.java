// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.operation.DurableMapOperation.MapItemContext;
import software.amazon.lambda.durable.operation.DurableWaitForCallbackOperation.WaitForCallbackContext;
import software.amazon.lambda.durable.operation.DurableWithRetryOperation.WithRetryContext;

class OperationContextTest {
    @Test
    void mapItemContextRestoresNestedScope() {
        assertThrows(IllegalStateException.class, MapItemContext::getCurrentContext);

        try (var outer = MapItemContext.attach(2)) {
            assertEquals(2, MapItemContext.getCurrentContext().getIndex());
            try (var inner = MapItemContext.attach(7)) {
                assertEquals(7, MapItemContext.getCurrentContext().getIndex());
            }
            assertEquals(2, MapItemContext.getCurrentContext().getIndex());
        }

        assertThrows(IllegalStateException.class, MapItemContext::getCurrentContext);
    }

    @Test
    void waitForCallbackContextRestoresNestedScope() {
        assertThrows(IllegalStateException.class, WaitForCallbackContext::getCurrentContext);

        try (var outer = WaitForCallbackContext.attach("outer")) {
            assertEquals("outer", WaitForCallbackContext.getCurrentContext().getCallbackId());
            try (var inner = WaitForCallbackContext.attach("inner")) {
                assertEquals("inner", WaitForCallbackContext.getCurrentContext().getCallbackId());
            }
            assertEquals("outer", WaitForCallbackContext.getCurrentContext().getCallbackId());
        }

        assertThrows(IllegalStateException.class, WaitForCallbackContext::getCurrentContext);
    }

    @Test
    void withRetryContextRestoresNestedScope() {
        assertThrows(IllegalStateException.class, WithRetryContext::getCurrentContext);

        try (var outer = WithRetryContext.attach(1)) {
            assertEquals(1, WithRetryContext.getCurrentContext().getAttempt());
            try (var inner = WithRetryContext.attach(2)) {
                assertEquals(2, WithRetryContext.getCurrentContext().getAttempt());
            }
            assertEquals(1, WithRetryContext.getCurrentContext().getAttempt());
        }

        assertThrows(IllegalStateException.class, WithRetryContext::getCurrentContext);
    }

    @Test
    void operationContextFailureNamesRequestedContext() {
        var exception = assertThrows(IllegalStateException.class, MapItemContext::getCurrentContext);

        assertTrue(exception.getMessage().contains("MapItemContext"));
    }
}
