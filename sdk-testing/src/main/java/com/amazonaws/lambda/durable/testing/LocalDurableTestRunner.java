// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.*;
import com.amazonaws.lambda.durable.model.DurableExecutionInput;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.services.lambda.runtime.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;

public class LocalDurableTestRunner<I, O> {
    private static final int MAX_INVOCATIONS = 100;

    private final Class<I> inputType;
    private final BiFunction<I, DurableContext, O> handler;
    private final LocalMemoryExecutionClient storage;
    private final SerDes serDes;

    public LocalDurableTestRunner(Class<I> inputType, BiFunction<I, DurableContext, O> handler) {
        this.inputType = inputType;
        this.handler = handler;
        this.storage = new LocalMemoryExecutionClient();
        this.serDes = new JacksonSerDes();
    }

    /** Run a single invocation (may return PENDING if waiting/retrying). */
    public TestResult<O> run(I input) {
        var durableInput = createDurableInput(input);
        var output = DurableExecutor.execute(durableInput, mockLambdaContext(), inputType, handler, storage);

        return new TestResult<>(output, storage);
    }

    /** Run until completion (SUCCEEDED or FAILED), simulating Lambda re-invocations. */
    public TestResult<O> runUntilComplete(I input) {
        TestResult<O> result = null;
        for (int i = 0; i < MAX_INVOCATIONS; i++) {
            result = run(input);
            if (result.getStatus() == ExecutionStatus.SUCCEEDED || result.getStatus() == ExecutionStatus.FAILED) {
                return result;
            }
            // Simulate time passing for PENDING operations by advancing any timers
            storage.advanceReadyOperations();
        }
        return result;
    }

    private DurableExecutionInput createDurableInput(I input) {
        var inputJson = serDes.serialize(input);
        var executionOp = Operation.builder()
                .id("0")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload(inputJson).build())
                .build();

        // Simulate what Lambda actually does: load previous operations and include them in InitialExecutionState
        var existingOps = storage.getExecutionState("arn:aws:lambda:us-east-1:123456789012:function:test", null)
                .operations();
        var allOps = new ArrayList<>(List.of(executionOp));
        allOps.addAll(existingOps);

        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test",
                "test-token",
                new DurableExecutionInput.InitialExecutionState(allOps, null));
    }

    private Context mockLambdaContext() {
        return null; // Minimal - tests don't need real Lambda context
    }

    /** Get the storage client for test setup (e.g., pre-populating operations). */
    public LocalMemoryExecutionClient getStorage() {
        return storage;
    }
}
