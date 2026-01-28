// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.exception.CallbackFailedException;
import com.amazonaws.lambda.durable.exception.CallbackTimeoutException;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;

class CallbackIntegrationTest {

    /** Custom SerDes that tracks deserialization calls for testing. */
    static class TrackingSerDes implements SerDes {
        private final JacksonSerDes delegate = new JacksonSerDes();
        private final AtomicInteger deserializeCount = new AtomicInteger(0);

        @Override
        public String serialize(Object value) {
            return delegate.serialize(value);
        }

        @Override
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            deserializeCount.incrementAndGet();
            return delegate.deserialize(data, typeToken);
        }

        public int getDeserializeCount() {
            return deserializeCount.get();
        }
    }

    @Test
    void callbackSuccessFlow() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var cb = ctx.createCallback("approval", String.class);
            return cb.get();
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
            return cb.get();
        });

        // First run - creates callback, suspends
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Simulate external system failing callback
        var callbackId = runner.getCallbackId("approval");
        var error = ErrorObject.builder()
                .errorType("Rejected")
                .errorMessage("Request denied")
                .build();
        runner.failCallback(callbackId, error);

        // Re-run - callback failed, throws CallbackFailedException
        result = runner.run("test");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(result.getError().isPresent());
        assertEquals(
                CallbackFailedException.class.getName(), result.getError().get().errorType());
        assertTrue(result.getError().get().errorMessage().contains("Rejected"));
    }

    @Test
    void callbackTimeoutFlow() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var cb = ctx.createCallback(
                    "approval",
                    String.class,
                    CallbackConfig.builder().timeout(Duration.ofMinutes(5)).build());
            return cb.get();
        });

        // First run - creates callback, suspends
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Simulate timeout
        var callbackId = runner.getCallbackId("approval");
        runner.timeoutCallback(callbackId);

        // Re-run - callback timed out, throws CallbackTimeoutException
        result = runner.run("test");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(result.getError().isPresent());
        assertEquals(
                CallbackTimeoutException.class.getName(),
                result.getError().get().errorType());
    }

    @Test
    void multipleCallbacksInSameExecution() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var cb1 = ctx.createCallback("approval1", String.class);
            var cb2 = ctx.createCallback("approval2", String.class);

            var result1 = cb1.get();
            var result2 = cb2.get();

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
            var approval = cb.get();

            return ctx.step("finalize", String.class, () -> step1 + " -> " + approval + " -> done");
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

    @Test
    void callbackWithCustomSerDes() {
        var customSerDes = new TrackingSerDes();

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var cb = ctx.createCallback(
                    "approval",
                    String.class,
                    CallbackConfig.builder().serDes(customSerDes).build());

            return cb.get();
        });

        // First run - creates callback, suspends
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete the callback
        var callbackId = runner.getCallbackId("approval");
        runner.completeCallback(callbackId, "\"approved\"");

        // Second run - callback complete, returns result
        result = runner.run("test");

        assertEquals("approved", result.getResult(String.class));
        assertTrue(customSerDes.getDeserializeCount() > 0, "Custom SerDes should have been used");
    }

    @Test
    void callbackWithNullSerDesUsesDefault() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            // Explicitly pass null SerDes - should use default
            var cb = ctx.createCallback(
                    "approval",
                    String.class,
                    CallbackConfig.builder().serDes(null).build());

            return cb.get();
        });

        // First run - creates callback, suspends
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete the callback
        var callbackId = runner.getCallbackId("approval");
        runner.completeCallback(callbackId, "\"result\"");

        // Second run - callback complete, returns result
        result = runner.run("test");

        assertEquals("result", result.getResult(String.class));
    }

    @Test
    void callbackFailedExceptionHandlesVariousErrorFormats() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var cb = ctx.createCallback("approval", String.class);
            return cb.get();
        });

        // First run - creates callback
        runner.run("test");

        // Fail callback with errorType, errorMessage, and stack trace
        var callbackId = runner.getCallbackId("approval");
        var error = ErrorObject.builder()
                .errorType("ValidationError")
                .errorMessage("Invalid input data")
                .stackTrace(java.util.List.of("com.example.Service|validate|Service.java|42"))
                .build();
        runner.failCallback(callbackId, error);

        // Second run - should fail with formatted message and preserved stack trace
        var result = runner.run("test");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(result.getError().isPresent());
        assertTrue(result.getError().get().errorMessage().contains("ValidationError"));
        assertTrue(result.getError().get().errorMessage().contains("Invalid input data"));
        assertNotNull(result.getError().get().stackTrace());
        assertEquals(1, result.getError().get().stackTrace().size());
    }
}
