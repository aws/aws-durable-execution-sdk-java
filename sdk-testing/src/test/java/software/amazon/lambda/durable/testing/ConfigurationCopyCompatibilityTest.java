// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationInfo;

class ConfigurationCopyCompatibilityTest {
    @Test
    void copyPreservesFactoryIdentityAndConfiguredBehavior() {
        var starts = new AtomicInteger();
        DurableExecutionPluginFactory factory = info -> new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                starts.incrementAndGet();
            }
        };
        var configured = DurableConfig.builder()
                .withDurableExecutionClient(mock(DurableExecutionClient.class))
                .withPlugins(factory)
                .withDeserializeAfterSerialization(false)
                .build();
        var copied = configured.toBuilder()
                .withDurableExecutionClient(mock(DurableExecutionClient.class))
                .build();
        assertSame(factory, copied.getPluginFactories().get(0));
        assertFalse(copied.shouldDeserializeAfterSerialization());
        var result = LocalDurableTestRunner.create(
                        String.class,
                        (input, context) -> context.step("copy-check", String.class, step -> input),
                        copied)
                .runUntilComplete("ok");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("ok", result.getResult(String.class));
        assertEquals(1, starts.get());
    }
}
