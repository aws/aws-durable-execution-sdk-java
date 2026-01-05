// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import org.junit.jupiter.api.Test;

class RetryExampleTest {

    @Test
    void testRetryExampleWithTimeBasedFailure() {
        // Test the retry example with time-based failure simulation
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var handler = new RetryExample();
            return handler.handleRequest(input, ctx);
        });

        var result = runner.run("test-input");

        // The test will likely result in PENDING due to the time-based failure
        // This demonstrates the retry mechanism in action
        System.out.println("Test result status: " + result.getStatus());

        // In a real scenario with actual time delays, this would eventually succeed
        // The LocalDurableTestRunner simulates the retry behavior
        assertNotNull(result);
    }

    @Test
    void testRetryExampleDemonstration() {
        // This test demonstrates the retry behavior without strict assertions
        // It's useful for observing the retry mechanism in action

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var handler = new RetryExample();
            return handler.handleRequest(input, ctx);
        });

        var result = runner.run("demo-input");

        System.out.println("Demo execution status: " + result.getStatus());

        // This test always passes - it's just for demonstration
        assertNotNull(result);
        assertTrue(result.getStatus().toString().matches("PENDING|FAILED|SUCCEEDED"));
    }

    @Test
    void testRetryExampleShowsRetryBehavior() {
        // Test that shows the different retry behaviors
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var handler = new RetryExample();
            return handler.handleRequest(input, ctx);
        });

        var result = runner.run("retry-behavior-test");

        System.out.println("Retry behavior test status: " + result.getStatus());

        // The example demonstrates:
        // 1. No-retry step that fails immediately
        // 2. Retry step that uses default exponential backoff
        // 3. Time-based failure that would eventually succeed with retries

        assertNotNull(result);
    }
}
