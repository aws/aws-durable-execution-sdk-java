// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class DeserializationFailedMapExampleTest {

    @Test
    void testDeserializationFailedExample() {
        var handler = new DeserializationFailedMapExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.runUntilComplete(new GreetingRequest("Alice"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(
                "Map iteration failed with error of type java.lang.RuntimeException. Message: Failure from Alice!",
                result.getResult(String.class));
    }
}
