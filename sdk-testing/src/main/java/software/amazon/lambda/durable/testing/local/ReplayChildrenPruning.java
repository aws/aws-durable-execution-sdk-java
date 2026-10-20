// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing.local;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;

/** Service visibility for invocation input and GetDurableExecutionState, without changing stored operations. */
final class ReplayChildrenPruning {
    private ReplayChildrenPruning() {}

    static List<Operation> visibleOperations(List<Operation> operations) {
        var byId = new HashMap<String, Operation>();
        for (var operation : operations) {
            if (operation.id() != null && !operation.id().isEmpty()) byId.put(operation.id(), operation);
        }
        var omitted = new HashMap<String, Boolean>();
        var visible = new ArrayList<Operation>();
        for (var operation : operations) {
            if (!hasPruningAncestor(operation, byId, omitted)) visible.add(operation);
        }
        return List.copyOf(visible);
    }

    private static boolean hasPruningAncestor(
            Operation operation, Map<String, Operation> byId, Map<String, Boolean> omitted) {
        var chain = new ArrayList<String>();
        var visited = new HashSet<String>();
        var current = operation;
        var hidden = false;
        while (current.parentId() != null && !current.parentId().isEmpty()) {
            if (current.id() != null) {
                var known = omitted.get(current.id());
                if (known != null) {
                    hidden = known;
                    break;
                }
                if (!visited.add(current.id())) break;
                chain.add(current.id());
            }
            var parent = byId.get(current.parentId());
            if (parent == null) break; // Missing ancestry does not prove that an operation must be omitted.
            if (prunesChildren(parent)) {
                hidden = true;
                break;
            }
            current = parent;
        }
        for (var id : chain) omitted.put(id, hidden);
        return hidden;
    }

    private static boolean prunesChildren(Operation operation) {
        return operation.type() == OperationType.CONTEXT
                && (operation.status() == OperationStatus.SUCCEEDED || operation.status() == OperationStatus.FAILED)
                && (operation.contextDetails() == null
                        || !Boolean.TRUE.equals(operation.contextDetails().replayChildren()));
    }
}
