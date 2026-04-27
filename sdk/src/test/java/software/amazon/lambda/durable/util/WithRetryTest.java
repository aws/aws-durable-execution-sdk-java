// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableContext;

class WithRetryTest {

    @Test
    void canBeImplementedAsLambda() {
        WithRetry<String> operation = (ctx, attempt) -> "result-" + attempt;
        var context = mock(DurableContext.class);

        assertEquals("result-1", operation.execute(context, 1));
        assertEquals("result-3", operation.execute(context, 3));
    }

    @Test
    void receivesContextAndAttempt() {
        var context = mock(DurableContext.class);
        WithRetry<String> operation = (ctx, attempt) -> {
            assertSame(context, ctx);
            return "attempt-" + attempt;
        };

        assertEquals("attempt-1", operation.execute(context, 1));
        assertEquals("attempt-2", operation.execute(context, 2));
    }

    @Test
    void canThrowExceptions() {
        WithRetry<String> operation = (ctx, attempt) -> {
            throw new RuntimeException("failed on attempt " + attempt);
        };
        var context = mock(DurableContext.class);

        var exception = assertThrows(RuntimeException.class, () -> operation.execute(context, 1));
        assertEquals("failed on attempt 1", exception.getMessage());
    }

    @Test
    void canReturnNull() {
        WithRetry<String> operation = (ctx, attempt) -> null;
        var context = mock(DurableContext.class);

        assertNull(operation.execute(context, 1));
    }
}
