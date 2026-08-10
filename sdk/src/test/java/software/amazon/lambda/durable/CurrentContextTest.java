// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.extension.ExtensionContext;

class CurrentContextTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void durableContextReturnsNullOutsideDurableThread() {
        assertNull(DurableContext.getCurrentContext());
    }

    @Test
    void durableContextCanBeRequiredOutsideDurableThread() {
        var exception = assertThrows(IllegalStateException.class, DurableContext::requireCurrentContext);

        assertTrue(exception.getMessage().contains("No DurableContext"));
    }

    @Test
    void durableContextFailsClearlyOnStepThread() {
        BaseContextImpl.setCurrentContext(mock(StepContext.class));

        var exception = assertThrows(IllegalStateException.class, DurableContext::getCurrentContext);

        assertTrue(exception.getMessage().contains("step thread"));
    }

    @Test
    void stepContextReturnsNullOutsideStepThread() {
        assertNull(StepContext.getCurrentContext());
    }

    @Test
    void stepContextCanBeRequiredOutsideStepThread() {
        var exception = assertThrows(IllegalStateException.class, StepContext::requireCurrentContext);

        assertTrue(exception.getMessage().contains("No StepContext"));
    }

    @Test
    void extensionContextReturnsActiveExtensionContext() {
        var context = mock(CurrentExtensionContext.class);
        BaseContextImpl.setCurrentContext(context);

        assertSame(context, ExtensionContext.getCurrentContext());
    }

    @Test
    void extensionContextFailsClearlyOnStepThread() {
        BaseContextImpl.setCurrentContext(mock(StepContext.class));

        var exception = assertThrows(IllegalStateException.class, ExtensionContext::getCurrentContext);

        assertTrue(exception.getMessage().contains("step thread"));
    }

    @Test
    void currentContextScopesRestorePreviousContext() {
        var outer = mock(DurableContext.class);
        var inner = mock(StepContext.class);
        BaseContextImpl.setCurrentContext(outer);

        try (var ignored = BaseContextImpl.attachCurrentContext(inner)) {
            assertSame(inner, StepContext.getCurrentContext());
            assertSame(inner, StepContext.requireCurrentContext());
        }

        assertSame(outer, DurableContext.getCurrentContext());
        assertSame(outer, DurableContext.requireCurrentContext());
    }

    private interface CurrentExtensionContext extends DurableContext, ExtensionContext {}
}
