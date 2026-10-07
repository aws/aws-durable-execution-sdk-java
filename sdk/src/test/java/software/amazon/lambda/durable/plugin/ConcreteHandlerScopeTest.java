// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ConcreteHandlerScopeTest {
    @Test
    void concreteScopeReturnTypeOpensAndClosesOnItsOwner() {
        var plugin = new ConcretePlugin();
        var runner = new PluginRunner(List.of(plugin));
        assertEquals("result", runner.runHandler(() -> {
            assertNotNull(plugin.scope);
            assertFalse(plugin.scope.closed);
            return "result";
        }));
        assertTrue(plugin.scope.closed);
        assertSame(Thread.currentThread(), plugin.scope.owner);
    }

    @HandlerScoped(ConcreteOpener.class)
    public static final class ConcretePlugin implements DurableExecutionPlugin {
        ConcreteScope scope;
    }

    public static final class ConcreteOpener implements Function<ConcretePlugin, ConcreteScope> {
        @Override
        public ConcreteScope apply(ConcretePlugin plugin) {
            return plugin.scope = new ConcreteScope();
        }
    }

    public static final class ConcreteScope implements AutoCloseable {
        final Thread owner = Thread.currentThread();
        boolean closed;

        @Override
        public void close() {
            assertSame(owner, Thread.currentThread());
            assertFalse(closed);
            closed = true;
        }
    }
}
