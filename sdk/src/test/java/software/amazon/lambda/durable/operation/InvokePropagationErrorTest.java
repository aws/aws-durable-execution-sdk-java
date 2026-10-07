// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.OperationInfo;
import software.amazon.lambda.durable.plugin.PluginRunner;
import software.amazon.lambda.durable.plugin.PropagationInput;
import software.amazon.lambda.durable.plugin.PropagationMetadata;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class InvokePropagationErrorTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void collectorErrorsRetainIdentityAndPreventCheckpoint(boolean linkageError) {
        Error failure = linkageError ? new NoSuchMethodError("plugin linkage") : new AssertionError("plugin error");
        var started = new AtomicBoolean();
        DurableExecutionPlugin plugin = new DurableExecutionPlugin() {
            @Override
            public void onOperationStart(OperationInfo info) {
                started.set(true);
            }

            @Override
            public PropagationMetadata providePropagationMetadata(PropagationInput input) {
                assertTrue(started.get(), "Operation context is established before collection");
                throw failure;
            }
        };
        var manager = mock(ExecutionManager.class);
        when(manager.getDurableExecutionArn()).thenReturn("execution-arn");
        var config = mock(DurableConfig.class);
        when(config.getPluginRunner()).thenReturn(new PluginRunner(List.of(plugin)));
        var context = mock(DurableContextImpl.class);
        when(context.getExecutionManager()).thenReturn(manager);
        when(context.getDurableConfig()).thenReturn(config);
        var operation = new InvokeOperation<>(
                OperationIdentifier.of("invoke-id", "invoke", OperationSubType.CHAINED_INVOKE),
                "target",
                "payload",
                TypeToken.get(String.class),
                InvokeConfig.builder().serDes(new JacksonSerDes()).build(),
                context);
        assertSame(failure, assertThrows(Error.class, operation::execute));
        verify(manager, never()).sendOperationUpdate(any());
    }
}
