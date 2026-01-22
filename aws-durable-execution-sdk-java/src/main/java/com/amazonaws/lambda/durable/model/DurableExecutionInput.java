// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.model;

import java.util.List;
import software.amazon.awssdk.services.lambda.model.Operation;

public record DurableExecutionInput(
        String durableExecutionArn, String checkpointToken, InitialExecutionState initialExecutionState) {
    public record InitialExecutionState(List<Operation> operations, String nextMarker) {}
}
