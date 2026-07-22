// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.otel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.examples.types.GreetingRequest;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class OtelXRayStepExampleTest {

    @BeforeEach
    void setUp() {
        OtelXRayExampleTestSupport.installGlobalOpenTelemetry();
    }

    @AfterEach
    void tearDown() {
        OtelXRayExampleTestSupport.resetGlobalOpenTelemetry();
    }

    @Test
    void testSimpleSteps_succeeds() {
        var handler = new OtelXRayStepExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.run(new GreetingRequest("Alice"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("HELLO, ALICE!", result.getResult(String.class));
    }

    @Test
    void testReplay_returnsSameResult() {
        var handler = new OtelXRayStepExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var input = new GreetingRequest("Bob");
        var result1 = runner.run(input);
        var result2 = runner.run(input);

        assertEquals(result1.getResult(String.class), result2.getResult(String.class));
    }

    @Test
    void testDefaultName() {
        var handler = new OtelXRayStepExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        var result = runner.run(new GreetingRequest());

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("HELLO, WORLD!", result.getResult(String.class));
    }
}
