// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.exception.CallbackFailedException;
import com.amazonaws.lambda.durable.exception.CallbackTimeoutException;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;

class CallbackIntegrationTest {

    @Test
    void callbackSuccessFlow() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var cb = ctx.createCallback("approval", String.class);
            return cb.future().get();
        });

        // First run - creates callback, suspends
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        var op = runner.getOperation("approval");
        assertNotNull(op);
        assertEquals(OperationType.CALLBACK, op.getType());
        assertEquals(OperationStatus.STARTED, op.getStatus());

        // Simulate external system completing callback
        var callbackId = runner.getCallbackId("approval");
        assertNotNull(callbackId);
        runner.completeCallback(callbackId, "\"approved\"");

        // Re-run - callback complete, returns result
        result = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("approved", result.getResult(String.class));
    }

    @Test
    void callbackFailureFlow() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var cb = ctx.createCallback("approval", String.class);
            return cb.future().get();
        });

        // First run - creates callback, suspends
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Simulate external system failing callback
        var callbackId = runner.getCallbackId("approval");
        var error = ErrorObject.builder().errorType("Rejected").errorMessage("Request denied").build();
        runner.failCallback(callbackId, error);

        // Re-run - callback failed, throws exception
        result = runner.run("test");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
    }

    @Test
    void callbackTimeoutFlow() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var cb = ctx.createCallback(
                    "approval", String.class, CallbackConfig.builder().timeout(Duration.ofMinutes(5)).build());
            return cb.future().get();
        });

        // First run - creates callback, suspends
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Simulate timeout
        var callbackId = runner.getCallbackId("approval");
        runner.timeoutCallback(callbackId);

        // Re-run - callback timed out, throws exception
        result = runner.run("test");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
    }

    @Test
    void multipleCallbacksInSameExecution() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var cb1 = ctx.createCallback("approval1", String.class);
            var cb2 = ctx.createCallback("approval2", String.class);

            var result1 = cb1.future().get();
            var result2 = cb2.future().get();

            return result1 + " and " + result2;
        });

        // First run - creates both callbacks, suspends on first
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete first callback
        var callbackId1 = runner.getCallbackId("approval1");
        runner.completeCallback(callbackId1, "\"first\"");

        // Second run - first callback done, suspends on second
        result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete second callback
        var callbackId2 = runner.getCallbackId("approval2");
        runner.completeCallback(callbackId2, "\"second\"");

        // Third run - both callbacks done, returns result
        result = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("first and second", result.getResult(String.class));
    }

    @Test
    void callbackWithSteps() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var step1 = ctx.step("prepare", String.class, () -> "prepared");

            var cb = ctx.createCallback("approval", String.class);
            var approval = cb.future().get();

            var step2 = ctx.step("finalize", String.class, () -> step1 + " -> " + approval + " -> done");

            return step2;
        });

        // First run - step1 completes, callback created, suspends
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete callback
        var callbackId = runner.getCallbackId("approval");
        runner.completeCallback(callbackId, "\"approved\"");

        // Second run - callback done, step2 completes
        result = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("prepared -> approved -> done", result.getResult(String.class));
    }
}
