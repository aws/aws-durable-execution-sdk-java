// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;

class ConfigurationCopyCompatibilityTest {
    @Test
    void missingCopyCapabilityNeverCallsNewMethodAndPreservesWorkingConfiguration() {
        var starts = new AtomicInteger();
        var plugin = new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                starts.incrementAndGet();
            }
        };
        var configured = DurableConfig.builder()
                .withDurableExecutionClient(mock(DurableExecutionClient.class))
                .withPlugins(plugin)
                .build();
        var older = mock(DurableConfig.class, delegatesTo(configured));
        doThrow(new NoSuchMethodError("toBuilder is absent on older core")).when(older).toBuilder();
        // Object models a visible API that does not declare the newer capability. Every older API getter delegates
        // to real configured state, while any accidental direct call to the new method fails like the released ABI.
        var copied = LocalDurableTestRunner.copyConfiguration(older, Object.class)
                .withDurableExecutionClient(mock(DurableExecutionClient.class))
                .build();
        var result = LocalDurableTestRunner.create(
                        String.class,
                        (input, context) -> context.step("copy-check", String.class, step -> input),
                        copied)
                .runUntilComplete("ok");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("ok", result.getResult(String.class));
        assertEquals(1, starts.get());
        verify(older, never()).toBuilder();
    }
}
