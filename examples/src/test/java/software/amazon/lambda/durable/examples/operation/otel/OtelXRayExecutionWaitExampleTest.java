// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.otel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class OtelXRayExecutionWaitExampleTest {

    @BeforeEach
    void setUp() {
        OtelXRayExampleTestSupport.installGlobalOpenTelemetry();
    }

    @AfterEach
    void tearDown() {
        OtelXRayExampleTestSupport.resetGlobalOpenTelemetry();
    }

    @Test
    void testFirstInvocation_suspendsOnWait() {
        var handler = new OtelXRayExecutionWaitExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.run(new GreetingRequest("Alice"));

        assertEquals(ExecutionStatus.PENDING, result.getStatus());
    }

    @Test
    void testFullExecution_completesAfterWait() {
        var handler = new OtelXRayExecutionWaitExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.runUntilComplete(new GreetingRequest("Alice"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertTrue(
                result.getResult(String.class).contains("Resumed and completed"),
                "Expected result to contain 'Resumed and completed', got: " + result.getResult(String.class));
    }
}
