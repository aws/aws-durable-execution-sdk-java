// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.client.DurableExecutionClient;

public class TestUtils {

    public static DurableExecutionClient createMockClient() {
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any())).thenAnswer(invocation -> {
            var updates = (List<OperationUpdate>) invocation.getArgument(2);
            var responseOperations = new ArrayList<Operation>();

            if (updates != null) {
                for (var update : updates) {
                    if (update.action() == OperationAction.START) {
                        var opBuilder = Operation.builder()
                                .id(update.id())
                                .name(update.name())
                                .type(update.type())
                                .status(OperationStatus.STARTED);

                        // Add callback details for CALLBACK operations
                        if (update.type() == OperationType.CALLBACK) {
                            opBuilder.callbackDetails(CallbackDetails.builder()
                                    .callbackId(UUID.randomUUID().toString())
                                    .build());
                        }

                        responseOperations.add(opBuilder.build());
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

    public static String hashOperationId(String rawId) {
        try {
            var messageDigest = MessageDigest.getInstance("SHA-256");
            var hash = messageDigest.digest(rawId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
