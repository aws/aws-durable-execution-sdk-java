// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;

class InvokeExampleTest {

    @Test
    void testSimpleInvokeExample_completeSequentially() {
        var handler = new SimpleInvokeExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        // First run
        var input = new GreetingRequest("world");
        var output1 = runner.run(input);

        assertEquals(ExecutionStatus.PENDING, output1.getStatus());

        // Second run
        runner.completeChainedInvoke("call-greeting1", "\"hello\"");
        var output2 = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, output2.getStatus());

        // Third run
        runner.completeChainedInvoke("call-greeting2", "\"world\"");
        var output3 = runner.run(input);
        assertEquals(ExecutionStatus.SUCCEEDED, output3.getStatus());
        assertEquals("helloworld", output3.getResult(String.class));
    }

    @Test
    void testSimpleInvokeExample_completeConcurrently() {
        var handler = new SimpleInvokeExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        // First run
        var input = new GreetingRequest("world");
        var output1 = runner.run(input);

        assertEquals(ExecutionStatus.PENDING, output1.getStatus());

        // Second run
        runner.completeChainedInvoke("call-greeting1", "\"hello\"");
        runner.completeChainedInvoke("call-greeting2", "\"world\"");
        var output2 = runner.run(input);
        assertEquals(ExecutionStatus.SUCCEEDED, output2.getStatus());
        assertEquals("helloworld", output2.getResult(String.class));
    }

    @Test
    void testSimpleInvokeExample_failFirst() {
        var handler = new SimpleInvokeExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        // First run
        var input = new GreetingRequest("world");
        var output1 = runner.run(input);

        assertEquals(ExecutionStatus.PENDING, output1.getStatus());

        // Second run, fail the async invoke
        runner.failChainedInvoke("call-greeting1", ErrorObject.builder().build());
        var output2 = runner.run(input);
        assertEquals(ExecutionStatus.PENDING, output2.getStatus());

        // Third run
        runner.completeChainedInvoke("call-greeting2", "\"world\"");
        var output3 = runner.run(input);
        assertEquals(ExecutionStatus.FAILED, output3.getStatus());
    }

    @Test
    void testSimpleInvokeExample_failSecond() {
        var handler = new SimpleInvokeExample();
        var runner = LocalDurableTestRunner.create(GreetingRequest.class, handler);

        // First run
        var input = new GreetingRequest("world");
        var output1 = runner.run(input);

        assertEquals(ExecutionStatus.PENDING, output1.getStatus());

        // Second run, fail the async invoke
        runner.failChainedInvoke("call-greeting2", ErrorObject.builder().build());
        var output2 = runner.run(input);
        assertEquals(ExecutionStatus.FAILED, output2.getStatus());
    }
}
