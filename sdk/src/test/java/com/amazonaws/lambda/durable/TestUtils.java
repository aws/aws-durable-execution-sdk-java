// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.services.lambda.model.*;

public class TestUtils {
    
    public static DurableExecutionClient createMockClient() {
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any()))
                .thenAnswer(invocation -> {
                    var updates = (List<OperationUpdate>) invocation.getArgument(2);
                    var responseOperations = new ArrayList<Operation>();
                    
                    if (updates != null) {
                        for (var update : updates) {
                            if (update.action() == OperationAction.START) {
                                responseOperations.add(Operation.builder()
                                        .id(update.id())
                                        .name(update.name())
                                        .type(update.type())
                                        .status(OperationStatus.STARTED)
                                        .build());
                            } else if (update.action() == OperationAction.SUCCEED) {
                                var stepDetails = StepDetails.builder();
                                if (update.payload() != null) {
                                    stepDetails.result(update.payload());
                                }
                                responseOperations.add(Operation.builder()
                                        .id(update.id())
                                        .name(update.name())
                                        .type(update.type())
                                        .status(OperationStatus.SUCCEEDED)
                                        .stepDetails(stepDetails.build())
                                        .build());
                            }
                        }
                    }
                    
                    return CheckpointDurableExecutionResponse.builder()
                            .checkpointToken("new-token")
                            .newExecutionState(CheckpointUpdatedExecutionState.builder()
                                    .operations(responseOperations)
                                    .build())
                            .build();
                });
        return client;
    }
}
