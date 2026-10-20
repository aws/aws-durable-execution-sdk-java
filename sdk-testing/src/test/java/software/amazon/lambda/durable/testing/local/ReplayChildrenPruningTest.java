// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing.local;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

class ReplayChildrenPruningTest {
    // The service test tree also reproduced by JS PR #1002.
    private static final List<Operation> TREE = List.of(
            step("1-step", null),
            context("2-context", null, OperationStatus.STARTED, null),
            step("2.1-step", "2-context").toBuilder()
                    .status(OperationStatus.PENDING)
                    .build(),
            step("3-step", "missing-parent"),
            context("4-context", null, OperationStatus.FAILED, null),
            context("4.1-context", "4-context", OperationStatus.FAILED, true),
            step("4.1.1-step", "4.1-context"),
            step("4.1.2-step", "4.1-context"),
            context("5-context", null, OperationStatus.SUCCEEDED, true),
            context("5.1-context", "5-context", OperationStatus.SUCCEEDED, false),
            step("5.1.1-step", "5.1-context"),
            step("5.1-step", "5-context"));
    private static final List<String> VISIBLE =
            List.of("1-step", "2-context", "2.1-step", "3-step", "4-context", "5-context", "5.1-context", "5.1-step");

    @Test
    void matchesTheServiceTreeWithoutChangingStoredOperations() {
        assertEquals(VISIBLE, ids(ReplayChildrenPruning.visibleOperations(TREE)));
        assertEquals(12, TREE.size());
        assertEquals(OperationStatus.FAILED, TREE.get(4).status());
    }

    @Test
    void descendantsMayAppearBeforeTheirAncestors() {
        var reversed = new ArrayList<>(TREE);
        Collections.reverse(reversed);
        var expected = new ArrayList<>(VISIBLE);
        Collections.reverse(expected);
        assertEquals(expected, ids(ReplayChildrenPruning.visibleOperations(reversed)));
    }

    @Test
    void startedIntermediateContextDoesNotHideACompletedAncestor() {
        var operations = List.of(
                step("leaf", "running"),
                context("running", "completed", OperationStatus.STARTED, true),
                context("completed", null, OperationStatus.SUCCEEDED, null));
        assertEquals(List.of("completed"), ids(ReplayChildrenPruning.visibleOperations(operations)));
    }

    @Test
    void runningContextsAndUnknownParentsRemainVisible() {
        var operations = List.of(
                step("leaf", "running"),
                context("running", null, OperationStatus.STARTED, false),
                step("orphan", "absent"));
        assertEquals(ids(operations), ids(ReplayChildrenPruning.visibleOperations(operations)));
    }

    @Test
    void malformedParentCycleTerminatesWithoutMutatingTheStore() {
        var operations = List.of(
                context("a", "b", OperationStatus.STARTED, null), context("b", "a", OperationStatus.STARTED, null));
        assertEquals(List.of("a", "b"), ids(ReplayChildrenPruning.visibleOperations(operations)));
    }

    @Test
    void statePruningDoesNotFilterCheckpointResponsesOrUpdatedIds() {
        var client = new LocalMemoryExecutionClient();
        var parent = OperationUpdate.builder().id("parent").name("parent").type(OperationType.CONTEXT);
        var callback = OperationUpdate.builder()
                .id("callback")
                .name("external")
                .parentId("parent")
                .type(OperationType.CALLBACK)
                .action(OperationAction.START)
                .build();
        client.checkpoint(
                "arn", "token", List.of(parent.action(OperationAction.START).build(), callback));
        client.checkpoint(
                "arn",
                "token",
                List.of(parent.action(OperationAction.SUCCEED)
                        .payload("\"saved\"")
                        .build()));
        client.completeCallback(client.getCallbackId("external"), OperationResult.succeeded("\"done\""));

        var delivered = client.getExecutionState("arn", "token", null);
        assertEquals(List.of("parent"), ids(delivered.operations()));
        assertNull(delivered.nextMarker());
        assertEquals(List.of("callback"), client.getUpdatedOperationIdsSinceLastInvocation());
        assertEquals(List.of("parent", "callback"), ids(client.getAllOperations()));
        assertEquals(
                OperationStatus.SUCCEEDED, client.getOperationByName("external").status());
        assertEquals(
                "\"done\"",
                client.getOperationByName("external").callbackDetails().result());
        var response = client.checkpoint("arn", "token", List.of());
        assertEquals(List.of("callback"), ids(response.newExecutionState().operations()));
        assertEquals(
                List.of("parent"),
                ids(client.getExecutionState("arn", "token", null).operations()));
    }

    private static List<String> ids(List<Operation> operations) {
        return operations.stream().map(Operation::id).toList();
    }

    private static Operation step(String id, String parent) {
        return Operation.builder()
                .id(id)
                .parentId(parent)
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .build();
    }

    private static Operation context(String id, String parent, OperationStatus status, Boolean replay) {
        return Operation.builder()
                .id(id)
                .parentId(parent)
                .type(OperationType.CONTEXT)
                .status(status)
                .contextDetails(ContextDetails.builder().replayChildren(replay).build())
                .build();
    }
}
