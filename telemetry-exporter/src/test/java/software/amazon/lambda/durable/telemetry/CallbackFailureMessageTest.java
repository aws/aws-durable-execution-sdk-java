// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class CallbackFailureMessageTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void typeOnlyFailureSurvivesTelemetryAndReplay(boolean caught) {
        List<DurableExecutionTelemetryRecord> records = new CopyOnWriteArrayList<>();
        var plugin = DurableExecutionTelemetry.durableExecutionTelemetry(DurableExecutionTelemetryConfig.builder()
                .samplingRate(1)
                .addExporter(records::add)
                .build());
        var effects = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    context.step("once", String.class, step -> {
                        effects.incrementAndGet();
                        return "done";
                    });
                    try {
                        return context.createCallback("approval", String.class).get();
                    } catch (CallbackFailedException failure) {
                        if (!caught) {
                            throw failure;
                        }
                        assertEquals("RejectedError", failure.getMessage());
                        assertNull(failure.getErrorObject().errorMessage());
                        context.wait("after-failure", Duration.ofSeconds(1));
                        return failure.getMessage();
                    }
                },
                DurableConfig.builder().withPlugins(plugin).build());

        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        var callbackId = runner.getCallbackId("approval");
        var error = ErrorObject.builder().errorType("RejectedError").build();
        runner.failCallback(callbackId, error);
        var result = runner.runUntilComplete("input");

        assertEquals(caught ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED, result.getStatus());
        assertEquals(1, effects.get(), "the completed step must not run again");
        assertEquals(callbackId, runner.getCallbackId("approval"));
        assertEquals(error, result.getOperation("approval").getCallbackDetails().error());
        var record = records.get(records.size() - 1);
        assertEquals(caught ? "SUCCEEDED" : "FAILED", record.status());
        for (var operation : record.operations()) {
            if (operation.error() != null && operation.error().message() != null) {
                assertFalse(operation.error().message().contains(": null"));
            }
        }
        if (caught) {
            assertEquals("RejectedError", result.getResult(String.class));
            assertNull(record.error);
        } else {
            assertEquals("RejectedError", record.error.name());
            assertEquals("RejectedError", record.error.message());
            assertEquals(record.error.toWireMap(), record.toByNameWireMap().get("error"));
        }
    }
}
