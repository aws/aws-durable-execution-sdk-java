// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.DurableContext;
import com.amazonaws.lambda.durable.DurableExecutor;
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
    private boolean skipTime = true; // Default to skipping time

    private LocalDurableTestRunner(Class<I> inputType, BiFunction<I, DurableContext, O> handler) {
        this.inputType = inputType;
        this.handler = handler;
        this.storage = new LocalMemoryExecutionClient();
        this.serDes = new JacksonSerDes();
    }

    public static <I, O> LocalDurableTestRunner<I, O> create(
            Class<I> inputType, BiFunction<I, DurableContext, O> handler) {
        return new LocalDurableTestRunner<>(inputType, handler);
    }

    public LocalDurableTestRunner<I, O> withSkipTime(boolean skipTime) {
        this.skipTime = skipTime;
        return this;
    }

    /** Run a single invocation (may return PENDING if waiting/retrying). */
    public TestResult<O> run(I input) {
        var durableInput = createDurableInput(input);
        var output = DurableExecutor.execute(durableInput, mockLambdaContext(), inputType, handler, storage);

        return storage.toTestResult(output);
    }

    /** Run until completion (SUCCEEDED or FAILED), simulating Lambda re-invocations. */
    public TestResult<O> runUntilComplete(I input) {
        TestResult<O> result = null;
        for (int i = 0; i < MAX_INVOCATIONS; i++) {
            result = run(input);

            if (result.getStatus() != ExecutionStatus.PENDING) {
                return result; // SUCCEEDED or FAILED - we're done
            }

            if (skipTime) {
                storage.advanceReadyOperations(); // Auto-advance and continue loop
            } else {
                return result; // Return PENDING - let test manually advance time
            }
        }
        return result;
    }

    public void resetCheckpointToStarted(String stepName) {
        storage.resetCheckpointToStarted(stepName);
    }

    public void simulateFireAndForgetCheckpointLoss(String stepName) {
        storage.simulateFireAndForgetCheckpointLoss(stepName);
    }

    public TestOperation getOperation(String name) {
        var op = storage.getOperationByName(name);
        return op != null ? new TestOperation(op, serDes) : null;
    }

    // Manual time advancement for skipTime=false scenarios
    public void advanceTime() {
        storage.advanceReadyOperations();
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

        // Load previous operations and include them in InitialExecutionState
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
}
