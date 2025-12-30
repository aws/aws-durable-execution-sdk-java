// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.model.DurableExecutionOutput;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;

import java.util.List;
import java.util.Optional;

public class TestResult<O> {
    private final DurableExecutionOutput output;
    private final LocalMemoryExecutionClient storage;
    private final SerDes serDes;

    TestResult(DurableExecutionOutput output, LocalMemoryExecutionClient storage) {
        this.output = output;
        this.storage = storage;
        this.serDes = new JacksonSerDes();
    }

    public ExecutionStatus getStatus() {
        return output.status();
    }

    public O getResult(Class<O> resultType) {
        if (output.status() != ExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException("Execution did not succeed: " + output.status());
        }
        return serDes.deserialize(output.result(), resultType);
    }
    
    public Optional<ErrorObject> getError() {
        return Optional.ofNullable(output.error());
    }

    public List<TestOperation> getOperations() {
        return storage.getAllOperations().stream()
            .filter(op -> op.type() != OperationType.EXECUTION) // Exclude execution op
            .map(op -> new TestOperation(op, serDes))
            .toList();
    }
    
    public List<TestOperation> getSucceededOperations() {
        return getOperations().stream()
            .filter(op -> op.getStatus() == OperationStatus.SUCCEEDED)
            .toList();
    }
    
    public List<TestOperation> getFailedOperations() {
        return getOperations().stream()
            .filter(op -> op.getStatus() == OperationStatus.FAILED)
            .toList();
    }

    public LocalMemoryExecutionClient getStorage() {
        return storage;
    }

    public DurableExecutionOutput getOutput() {
        return output;
    }
}
