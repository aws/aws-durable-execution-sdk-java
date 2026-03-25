// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.parallel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class DeserializationFailedParallelExampleTest {

    @Test
    void testDeserializationFailedParallelExample() {
        var handler = new DeserializationFailedParallelExample();
        var runner = LocalDurableTestRunner.create(DeserializationFailedParallelExample.Input.class, handler);

        var input = new DeserializationFailedParallelExample.Input(List.of("apple", "banana", "cherry"));
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(String.class);
        assertEquals(
                "Parallel branch failed with error of type java.lang.RuntimeException. Message: Intentional failure for transform",
                output);
    }
}
