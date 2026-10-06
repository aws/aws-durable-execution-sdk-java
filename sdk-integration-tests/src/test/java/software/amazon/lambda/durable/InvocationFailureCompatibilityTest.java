// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;
import software.amazon.lambda.durable.util.ExceptionHelper;

class InvocationFailureCompatibilityTest {
    @ParameterizedTest
    @ValueSource(strings = {"handler", "input", "output"})
    void ordinaryExecutionExceptionKeepsItsIdentityAndSerializedMeaning(String stage) {
        var failure = new ExecutionException("application wrapper", new IllegalArgumentException("inner cause"));
        var serializedFailure = new AtomicReference<Object>();
        var endInfo = new AtomicReference<InvocationEndInfo>();
        var ends = new AtomicInteger();
        SerDes serDes = new SerDes() {
            private final JacksonSerDes delegate = new JacksonSerDes();

            public String serialize(Object value) {
                if (stage.equals("output") && "result".equals(value)) ExceptionHelper.sneakyThrow(failure);
                if (value instanceof Throwable) serializedFailure.set(value);
                return delegate.serialize(value);
            }

            public <T> T deserialize(String value, TypeToken<T> type) {
                if (stage.equals("input")) ExceptionHelper.sneakyThrow(failure);
                return delegate.deserialize(value, type);
            }
        };
        var config = DurableConfig.builder()
                .withDurableExecutionClient(new LocalMemoryExecutionClient())
                .withSerDes(serDes)
                .withPlugins(info -> new DurableExecutionPlugin() {
                    public void onInvocationEnd(InvocationEndInfo end) {
                        ends.incrementAndGet();
                        endInfo.set(end);
                    }
                })
                .build();
        if (stage.equals("output")) {
            assertSame(failure, assertThrows(ExecutionException.class, () -> execute(stage, failure, config)));
            assertEquals(InvocationStatus.RETRYING, endInfo.get().invocationStatus());
        } else {
            var output = execute(stage, failure, config);
            assertEquals(ExecutionStatus.FAILED, output.status());
            assertEquals(ExecutionException.class.getName(), output.error().errorType());
            assertEquals("application wrapper", output.error().errorMessage());
            assertSame(failure, serializedFailure.get());
            assertEquals(InvocationStatus.FAILED, endInfo.get().invocationStatus());
        }
        assertEquals(1, ends.get());
        assertSame(failure, endInfo.get().executionError());
    }

    private static DurableExecutionOutput execute(String stage, Throwable failure, DurableConfig config) {
        return DurableExecutor.execute(
                input(),
                null,
                TypeToken.get(String.class),
                (value, context) -> {
                    if (stage.equals("handler")) ExceptionHelper.sneakyThrow(failure);
                    return "result";
                },
                config);
    }

    private static DurableExecutionInput input() {
        var operation = Operation.builder()
                .id("id")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/id",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operation).build());
    }
}
