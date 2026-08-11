// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionOperation;

class ReservedOperationContextTest {
    @Test
    void suppliesReservedOperationThenDelegates() {
        var delegate = mock(ExtensionContext.class);
        var reserved = mock(ExtensionOperation.class);
        var next = mock(ExtensionOperation.class);
        when(delegate.reserve("next")).thenReturn(next);
        var context = new ReservedOperationContext(delegate, "node", reserved);

        assertSame(reserved, context.reserve("node"));
        assertSame(next, context.reserve("next"));

        verify(delegate).reserve("next");
    }

    @Test
    void rejectsUnexpectedFirstReservation() {
        var context =
                new ReservedOperationContext(mock(ExtensionContext.class), "node", mock(ExtensionOperation.class));

        assertThrows(IllegalStateException.class, () -> context.reserve("other"));
    }

    @Test
    void delegatesStableLocalReservations() {
        var delegate = mock(ExtensionContext.class);
        var local = mock(ExtensionOperation.class);
        when(delegate.reserve("child", "stable-id")).thenReturn(local);
        var context = new ReservedOperationContext(delegate, "node", mock(ExtensionOperation.class));

        assertSame(local, context.reserve("child", "stable-id"));

        verify(delegate).reserve("child", "stable-id");
    }
}
