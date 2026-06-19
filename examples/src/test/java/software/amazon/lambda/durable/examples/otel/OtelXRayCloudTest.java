// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.otel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/**
 * Cloud-like tests for OTel examples using LocalDurableTestRunner.
 *
 * <p>These verify that the OTel plugin doesn't break execution for various scenarios. When deployed to Lambda with
 * CloudDurableTestRunner, these same scenarios validate that flush/export works correctly under real concurrency.
 */
class OtelXRayCloudTest {

    @Test
    void mapExample_executesSuccessfully() {
        var handler = new OtelXRayMapExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.runUntilComplete(new GreetingRequest("test"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("Mapped 3 items", result.getResult(String.class));
    }

    @Test
    void parallelExample_executesSuccessfully() {
        var handler = new OtelXRayParallelExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.runUntilComplete(new GreetingRequest("test"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertTrue(result.getResult(String.class).contains("Parallel completed: 2 branches"));
    }

    @Test
    void nestedContextExample_executesSuccessfully() {
        var handler = new OtelXRayNestedContextExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.runUntilComplete(new GreetingRequest("World"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("HELLO, WORLD!", result.getResult(String.class));
    }
}
