// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.model.DurableExecutionOutput;
import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;

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

    public LocalMemoryExecutionClient getStorage() {
        return storage;
    }

    public DurableExecutionOutput getOutput() {
        return output;
    }
}
