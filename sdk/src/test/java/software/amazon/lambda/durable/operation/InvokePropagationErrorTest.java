// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationInfo;
import software.amazon.lambda.durable.plugin.PluginRunner;
import software.amazon.lambda.durable.plugin.PropagationInput;
import software.amazon.lambda.durable.plugin.PropagationMetadata;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class InvokePropagationErrorTest {
    private static final String HEADER = "Root=1-6955b900-123456789012345678901234;Parent=1234567890123456;Sampled=1";

    @ParameterizedTest
    @MethodSource("isolatedFailures")
    void optionalFailuresAllowLaterPluginAndPublicInvokeStart(Throwable failure) {
        var started = new AtomicBoolean();
        var laterCalls = new AtomicInteger();
        var manager = mock(ExecutionManager.class);
        when(manager.sendOperationUpdate(any())).thenReturn(CompletableFuture.completedFuture(null));
        DurableContext context = context(manager, List.of(failingPlugin(started, failure), healthyPlugin(laterCalls)));

        assertNotNull(assertDoesNotThrow(() -> context.invokeAsync(
                "invoke",
                "target:live",
                "payload",
                TypeToken.get(String.class),
                InvokeConfig.builder().tenantId("tenant").build())));

        assertTrue(started.get(), "Operation start runs before optional collection");
        assertEquals(1, laterCalls.get(), "A failed optional plugin must not skip later plugins");
        var checkpoint = ArgumentCaptor.forClass(OperationUpdate.class);
        verify(manager).sendOperationUpdate(checkpoint.capture());
        var update = checkpoint.getValue();
        assertEquals(OperationAction.START, update.action());
        assertEquals(OperationType.CHAINED_INVOKE, update.type());
        assertEquals("invoke", update.name());
        assertEquals("\"payload\"", update.payload());
        assertEquals("target:live", update.chainedInvokeOptions().functionName());
        assertEquals("tenant", update.chainedInvokeOptions().tenantId());
        assertEquals(HEADER, update.chainedInvokeOptions().xAmznTraceId());
        verify(manager).pollForOperationUpdates(update.id());
    }

    @ParameterizedTest
    @MethodSource("propagatedErrors")
    void otherCollectorErrorsRetainIdentityAndPreventCheckpoint(Error failure) {
        var started = new AtomicBoolean();
        var laterCalls = new AtomicInteger();
        var manager = mock(ExecutionManager.class);
        DurableContext context = context(manager, List.of(failingPlugin(started, failure), healthyPlugin(laterCalls)));

        assertSame(
                failure,
                assertThrows(
                        Error.class,
                        () -> context.invokeAsync(
                                "invoke",
                                "target:live",
                                "payload",
                                TypeToken.get(String.class),
                                InvokeConfig.builder().build())));
        assertTrue(started.get());
        assertEquals(0, laterCalls.get(), "Errors outside linkage isolation must still escape immediately");
        verify(manager, never()).sendOperationUpdate(any());
    }

    private static Stream<Throwable> isolatedFailures() {
        return Stream.of(
                new IllegalStateException("optional instrumentation failed"),
                new CancellationException("optional instrumentation cancelled"),
                new NoSuchMethodError("plugin linkage"),
                new NoClassDefFoundError("plugin dependency"));
    }

    @SuppressWarnings("removal")
    private static Stream<Error> propagatedErrors() {
        return Stream.of(new AssertionError("plugin error"), new InternalError("JVM fatal"), new ThreadDeath());
    }

    private static DurableExecutionPlugin failingPlugin(AtomicBoolean started, Throwable failure) {
        DurableExecutionPlugin plugin = new DurableExecutionPlugin() {
            @Override
            public void onOperationStart(OperationInfo info) {
                started.set(true);
            }

            @Override
            public PropagationMetadata providePropagationMetadata(PropagationInput input) {
                assertTrue(started.get(), "Operation context is established before collection");
                if (failure instanceof RuntimeException exception) throw exception;
                throw (Error) failure;
            }
        };
        return plugin;
    }

    private static DurableExecutionPlugin healthyPlugin(AtomicInteger calls) {
        return new DurableExecutionPlugin() {
            @Override
            public PropagationMetadata providePropagationMetadata(PropagationInput input) {
                calls.incrementAndGet();
                return new PropagationMetadata(HEADER);
            }
        };
    }

    private static DurableContext context(ExecutionManager manager, List<DurableExecutionPlugin> plugins) {
        when(manager.getDurableExecutionArn()).thenReturn("execution-arn");
        var config = mock(DurableConfig.class);
        var runner = new PluginRunner(plugins.stream()
                .map(plugin -> (DurableExecutionPluginFactory) ignored -> plugin)
                .toList());
        runner.onInvocationStart(new InvocationInfo("request", "execution-arn", true, Instant.EPOCH));
        when(manager.getPluginRunner()).thenReturn(runner);
        when(config.getSerDes()).thenReturn(new JacksonSerDes());
        return DurableContextImpl.createRootContext(manager, config, null);
    }
}
