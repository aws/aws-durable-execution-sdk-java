// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.operation.BaseDurableOperation;

/**
 * Utility methods for converting SDK internal types to plugin info records.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class PluginInfoConverter {

    private PluginInfoConverter() {}

    /**
     * Converts an SDK {@link Operation} to an {@link OperationInfo} using an {@link OperationIdentifier}.
     *
     * @param operation the SDK operation (may be null for first-start scenarios)
     * @param identifier the operation identifier containing id, name, type, and subType
     * @param parentId the parent operation ID (may be null for root operations)
     * @return an OperationInfo record
     */
    public static OperationInfo toOperationInfo(Operation operation, OperationIdentifier identifier, String parentId) {
        return new OperationInfo(
                identifier.operationId(),
                identifier.name(),
                identifier.operationType() != null ? identifier.operationType().toString() : null,
                identifier.subType() != null ? identifier.subType().getValue() : null,
                parentId,
                operation != null ? operation.startTimestamp() : Instant.now(),
                operation != null ? operation.endTimestamp() : null,
                operation != null && operation.status() != null
                        ? operation.status().toString()
                        : null,
                operation != null);
    }

    /**
     * Creates an {@link OperationEndInfo} from an SDK {@link Operation}, an {@link OperationIdentifier}, and an
     * optional error.
     *
     * @param operation the completed SDK operation
     * @param identifier the operation identifier containing id, name, type, and subType
     * @param parentId the parent operation ID (may be null)
     * @param error the error if the operation failed (may be null)
     * @return an OperationEndInfo record
     */
    public static OperationEndInfo toOperationEndInfo(
            Operation operation, OperationIdentifier identifier, String parentId, boolean isReplay, Throwable error) {
        return new OperationEndInfo(
                identifier.operationId(),
                identifier.name(),
                identifier.operationType() != null ? identifier.operationType().toString() : null,
                identifier.subType() != null ? identifier.subType().getValue() : null,
                parentId,
                operation != null ? operation.startTimestamp() : null,
                operation != null ? operation.endTimestamp() : null,
                operation != null && operation.status() != null
                        ? operation.status().toString()
                        : null,
                operation != null && operation.stepDetails() != null
                        ? operation.stepDetails().attempt()
                        : null,
                isReplay,
                error);
    }

    /**
     * Creates a {@link UserFunctionStartInfo} for when a user function starts executing.
     *
     * @param identifier the operation identifier containing id, name, type, and subType
     * @param parentId the parent operation ID (may be null)
     * @param isReplay true if this operation was already present in the checkpointed state when it started
     * @param isReplayingChildren true if the child operations of this context body are replaying from checkpoints
     * @param attempt the 1-based attempt number (null for context operations)
     * @return a UserFunctionStartInfo record
     */
    public static UserFunctionStartInfo toUserFunctionStartInfo(
            OperationIdentifier identifier,
            String parentId,
            boolean isReplay,
            boolean isReplayingChildren,
            Integer attempt) {
        return new UserFunctionStartInfo(
                identifier.operationId(),
                identifier.name(),
                identifier.operationType() != null ? identifier.operationType().toString() : null,
                identifier.subType() != null ? identifier.subType().getValue() : null,
                parentId,
                Instant.now(),
                isReplay,
                isReplayingChildren,
                attempt);
    }

    /**
     * Creates a {@link UserFunctionEndInfo} from a start info and outcome.
     *
     * @param startInfo the start info from when the function began
     * @param succeeded true if the function completed without error
     * @param error the error if the function failed (may be null)
     * @return a UserFunctionEndInfo record
     */
    public static UserFunctionEndInfo toUserFunctionEndInfo(
            UserFunctionStartInfo startInfo, boolean succeeded, Throwable error) {
        return new UserFunctionEndInfo(
                startInfo.id(),
                startInfo.name(),
                startInfo.type(),
                startInfo.subType(),
                startInfo.parentId(),
                startInfo.startTimestamp(),
                Instant.now(),
                startInfo.isReplay(),
                startInfo.isReplayingChildren(),
                startInfo.attempt(),
                succeeded,
                error);
    }

    /**
     * Creates an {@link OperationChangeInfo} from the durable operations whose status changed in a checkpoint response
     * and a snapshot of all operations tracked for the execution.
     *
     * @param requestId the Lambda request ID for the invocation
     * @param durableExecutionArn the durable execution ARN
     * @param updatedOperations the durable operations whose status changed in this checkpoint response
     * @param allOperations all durable operations tracked for the execution after this response
     * @param replayedOperationIds ids of the operations delivered in this invocation's initial state, used to populate
     *     each item's {@code isReplay} indicator
     * @return an OperationChangeInfo record
     */
    public static OperationChangeInfo toOperationChangeInfo(
            String requestId,
            String durableExecutionArn,
            Collection<Operation> updatedOperations,
            Collection<Operation> allOperations,
            Set<String> replayedOperationIds) {
        return new OperationChangeInfo(
                requestId,
                durableExecutionArn,
                toOperationItemMap(updatedOperations, replayedOperationIds),
                toOperationItemMap(allOperations, replayedOperationIds));
    }

    /**
     * Converts durable operations to an unmodifiable map of {@link OperationChangeItemInfo}, keyed by operation ID.
     *
     * @param operations the durable operations to convert
     * @param replayedOperationIds ids of the operations delivered in this invocation's initial state, used to populate
     *     each item's {@code isReplay} indicator
     * @return an unmodifiable map of operation ID to item info
     */
    public static Map<String, OperationChangeItemInfo> toOperationItemMap(
            Collection<Operation> operations, Set<String> replayedOperationIds) {
        return operations.stream()
                .collect(Collectors.toUnmodifiableMap(
                        Operation::id,
                        operation ->
                                toOperationChangeItemInfo(operation, replayedOperationIds.contains(operation.id()))));
    }

    private static OperationChangeItemInfo toOperationChangeItemInfo(Operation operation, boolean isReplay) {
        return new OperationChangeItemInfo(
                operation.id(),
                operation.name(),
                operation.typeAsString(),
                operation.subType(),
                operation.parentId(),
                operation.startTimestamp(),
                operation.endTimestamp(),
                operation.status(),
                operation.stepDetails() != null ? operation.stepDetails().attempt() : null,
                isReplay,
                BaseDurableOperation.extractErrorFromOperation(operation));
    }
}
