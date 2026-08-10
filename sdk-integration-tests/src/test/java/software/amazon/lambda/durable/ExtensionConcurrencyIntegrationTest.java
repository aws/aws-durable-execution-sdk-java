// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ExtensionConcurrencyIntegrationTest {
    @Test
    void anyOfSuspendsWhileCallbacksArePending() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var first = context.createCallback("first", String.class);
            var second = context.createCallback("second", String.class);
            return (String) DurableFuture.anyOf(first, second);
        });

        var pending = runner.run("input");

        assertEquals(ExecutionStatus.PENDING, pending.getStatus());

        runner.completeCallback(runner.getCallbackId("second"), "\"second-result\"");
        var completed = runner.run("input");

        assertEquals(ExecutionStatus.SUCCEEDED, completed.getStatus());
        assertEquals("second-result", completed.getResult(String.class));
    }
}
