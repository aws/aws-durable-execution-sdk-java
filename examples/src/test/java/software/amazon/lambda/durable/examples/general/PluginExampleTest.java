// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.general;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class PluginExampleTest {

    @Test
    void testPluginExample_executesSuccessfully() {
        var handler = new PluginExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.run(new GreetingRequest("World"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("HELLO, WORLD!", result.getResult(String.class));

        // Verify operations were tracked
        assertNotNull(result.getOperation("create-greeting"));
        assertNotNull(result.getOperation("transform"));
    }

    @Test
    void testPluginExample_pluginHooksFire() {
        // This test verifies that the plugin hooks fire without error.
        // Check stdout/CloudWatch for [PLUGIN] log lines when deployed.
        var handler = new PluginExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.runUntilComplete(new GreetingRequest("Test"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("HELLO, TEST!", result.getResult(String.class));
    }
}
