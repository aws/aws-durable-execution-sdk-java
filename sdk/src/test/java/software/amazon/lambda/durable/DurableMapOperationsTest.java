// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.context.BaseContextImpl;

class DurableMapOperationsTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void mapExposesItemIndexThroughScopedContext() {
        var context = mock(DurableContext.class);
        BaseContextImpl.setCurrentContext(context);

        DurableMapOperations.map("map", List.of("value"), String.class, item -> {
            assertEquals(3, MapItemContext.getCurrentContext().getIndex());
            return item.toUpperCase();
        });

        @SuppressWarnings("unchecked")
        var function = (ArgumentCaptor<DurableContext.MapFunction<String, String>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(DurableContext.MapFunction.class);
        verify(context).map(eq("map"), eq(List.of("value")), eq(String.class), function.capture());
        assertEquals("VALUE", function.getValue().apply("value", 3, mock(DurableContext.class)));
        assertThrows(IllegalStateException.class, MapItemContext::getCurrentContext);
    }
}
