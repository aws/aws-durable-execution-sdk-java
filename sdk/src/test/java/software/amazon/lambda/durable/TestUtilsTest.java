// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.plugin.PluginInfoConverter;

class TestUtilsTest {
    @Test
    void batchedUpdatesReturnLatestStatePerOperation() {
        var client = TestUtils.createMockClient();
        var response = client.checkpoint(
                "arn:execution",
                "token",
                List.of(
                        update("first", OperationAction.START, null),
                        update("second", OperationAction.START, null),
                        update("first", OperationAction.SUCCEED, "\"result\"")));
        var operations = response.newExecutionState().operations();

        // Duplicate IDs make the real SDK callback reject a successful checkpoint.
        var change = assertDoesNotThrow(() -> PluginInfoConverter.toOperationChangeInfo(
                "request", "arn:execution", operations, operations, Set.of()));
        assertEquals(2, operations.size());
        assertEquals(Set.of("first", "second"), change.updatedOperations().keySet());
        assertEquals(
                OperationStatus.SUCCEEDED,
                change.updatedOperations().get("first").status());
        assertEquals("\"result\"", change.updatedOperations().get("first").result());
        assertEquals(
                OperationStatus.STARTED,
                change.updatedOperations().get("second").status());
    }

    private static OperationUpdate update(String id, OperationAction action, String payload) {
        return OperationUpdate.builder()
                .id(id)
                .type(OperationType.STEP)
                .action(action)
                .payload(payload)
                .build();
    }
}
