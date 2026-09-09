// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class WorkflowInsightExampleTest {

    @Test
    void emitsInsightAndReturnsGreeting() {
        var handler = new WorkflowInsightExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.runUntilComplete(new GreetingRequest("Alice"));

        // The Workflow Insight plugin writes its record to stdout via LambdaLogExporter; we do not capture
        // global stdout here. The local assertions verify the execution succeeds and both named operations run,
        // which is what feeds the emitted operationsByName summaries.
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("HELLO, ALICE!", result.getResult(String.class));

        assertNotNull(result.getOperation("create-greeting"));
        assertNotNull(result.getOperation("transform"));
    }

    @Test
    void usesDefaultNameWhenAbsent() {
        var handler = new WorkflowInsightExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.runUntilComplete(new GreetingRequest());

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("HELLO, WORLD!", result.getResult(String.class));

        assertNotNull(result.getOperation("create-greeting"));
        assertNotNull(result.getOperation("transform"));
    }
}
