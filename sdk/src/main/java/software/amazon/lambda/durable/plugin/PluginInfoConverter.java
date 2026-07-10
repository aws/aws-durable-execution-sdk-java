// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;
import java.util.Collection;
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
                isReplay,
                error);
    }

    /**
     * Creates a {@link UserFunctionStartInfo} for when a user function starts executing.
     *
     * @param identifier the operation identifier containing id, name, type, and subType
     * @param parentId the parent operation ID (may be null)
     * @param isReplay true if the user function is called during replay (context operations)
     * @param attempt the 1-based attempt number (null for context operations)
     * @return a UserFunctionStartInfo record
     */
    public static UserFunctionStartInfo toUserFunctionStartInfo(
            OperationIdentifier identifier, String parentId, boolean isReplayingChildren, Integer attempt) {
        return new UserFunctionStartInfo(
                identifier.operationId(),
                identifier.name(),
                identifier.operationType() != null ? identifier.operationType().toString() : null,
                identifier.subType() != null ? identifier.subType().getValue() : null,
                parentId,
                Instant.now(),
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
     * @return an OperationChangeInfo record
     */
    public static OperationChangeInfo toOperationChangeInfo(
            String requestId,
            String durableExecutionArn,
            Collection<Operation> updatedOperations,
            Collection<Operation> allOperations) {
        return new OperationChangeInfo(
                requestId,
                durableExecutionArn,
                updatedOperations.stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Operation::id, PluginInfoConverter::toOperationChangeItemInfo)),
                allOperations.stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Operation::id, PluginInfoConverter::toOperationChangeItemInfo)));
    }

    private static OperationChangeItemInfo toOperationChangeItemInfo(Operation operation) {
        return new OperationChangeItemInfo(
                operation.id(),
                operation.name(),
                operation.typeAsString(),
                operation.subType(),
                operation.parentId(),
                operation.startTimestamp(),
                operation.endTimestamp(),
                BaseDurableOperation.extractErrorFromOperation(operation),
                operation.status());
    }
}
