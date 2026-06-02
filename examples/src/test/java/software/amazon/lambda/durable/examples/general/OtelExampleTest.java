// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.general;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class OtelExampleTest {

    @Test
    void testOtelExample_executesSuccessfully() {
        var handler = new OtelExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.run(new GreetingRequest("World"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("HELLO, WORLD!", result.getResult(String.class));

        assertNotNull(result.getOperation("create-greeting"));
        assertNotNull(result.getOperation("transform"));
    }
}
