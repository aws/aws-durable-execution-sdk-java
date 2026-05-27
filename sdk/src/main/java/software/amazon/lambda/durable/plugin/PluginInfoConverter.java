// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.model.OperationSubType;

/**
 * Utility methods for converting SDK internal types to plugin info records.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class PluginInfoConverter {

    private PluginInfoConverter() {}

    /**
     * Converts an SDK {@link Operation} to an {@link OperationInfo} for plugin hooks.
     *
     * @param operation the SDK operation (may be null for first-start scenarios)
     * @param operationId the hashed operation ID
     * @param rawId the unhashed operation ID (e.g. "1", "2", "1-1")
     * @param name the operation name
     * @param type the operation type
     * @param subType the operation sub-type (may be null)
     * @param parentId the hashed parent operation ID (may be null for root operations)
     * @param rawParentId the unhashed parent ID (may be null for root operations)
     * @return an OperationInfo record
     */
    public static OperationInfo toOperationInfo(
            Operation operation,
            String operationId,
            String rawId,
            String name,
            OperationType type,
            OperationSubType subType,
            String parentId,
            String rawParentId) {
        return new OperationInfo(
                operationId,
                rawId,
                name,
                type != null ? type.toString() : null,
                subType != null ? subType.getValue() : null,
                parentId,
                rawParentId,
                operation != null ? operation.startTimestamp() : null,
                operation != null ? operation.endTimestamp() : null);
    }

    /**
     * Converts an SDK {@link Operation} to an {@link OperationInfo} using the operation's own fields. Raw IDs are not
     * available from the Operation object alone, so they are set to null.
     *
     * @param operation the SDK operation
     * @return an OperationInfo record
     */
    public static OperationInfo toOperationInfo(Operation operation) {
        if (operation == null) {
            return new OperationInfo(null, null, null, null, null, null, null, null, null);
        }
        return new OperationInfo(
                operation.id(),
                null,
                operation.name(),
                operation.type() != null ? operation.type().toString() : null,
                operation.subType(),
                operation.parentId(),
                null,
                operation.startTimestamp(),
                operation.endTimestamp());
    }

    /**
     * Creates an {@link OperationEndInfo} from an SDK {@link Operation} and an optional error.
     *
     * @param operation the completed SDK operation
     * @param operationId the hashed operation ID
     * @param rawId the unhashed operation ID
     * @param name the operation name
     * @param type the operation type
     * @param subType the operation sub-type (may be null)
     * @param parentId the hashed parent operation ID (may be null)
     * @param rawParentId the unhashed parent ID (may be null)
     * @param error the error if the operation failed (may be null)
     * @return an OperationEndInfo record
     */
    public static OperationEndInfo toOperationEndInfo(
            Operation operation,
            String operationId,
            String rawId,
            String name,
            OperationType type,
            OperationSubType subType,
            String parentId,
            String rawParentId,
            Throwable error) {
        return new OperationEndInfo(
                operationId,
                rawId,
                name,
                type != null ? type.toString() : null,
                subType != null ? subType.getValue() : null,
                parentId,
                rawParentId,
                operation != null ? operation.startTimestamp() : null,
                operation != null ? operation.endTimestamp() : null,
                error);
    }

    /**
     * Creates a {@link UserFunctionStartInfo} for when a user function starts executing.
     *
     * @param operationId the hashed operation ID
     * @param rawId the unhashed operation ID
     * @param name the operation name
     * @param type the operation type
     * @param subType the operation sub-type (may be null)
     * @param parentId the hashed parent operation ID (may be null)
     * @param rawParentId the unhashed parent ID (may be null)
     * @param isReplay true if the user function is called during replay (context operations)
     * @param attempt the 1-based attempt number (null for context operations)
     * @return a UserFunctionStartInfo record
     */
    public static UserFunctionStartInfo toUserFunctionStartInfo(
            String operationId,
            String rawId,
            String name,
            OperationType type,
            OperationSubType subType,
            String parentId,
            String rawParentId,
            boolean isReplay,
            Integer attempt) {
        return new UserFunctionStartInfo(
                operationId,
                rawId,
                name,
                type != null ? type.toString() : null,
                subType != null ? subType.getValue() : null,
                parentId,
                rawParentId,
                Instant.now(),
                isReplay,
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
                startInfo.rawId(),
                startInfo.name(),
                startInfo.type(),
                startInfo.subType(),
                startInfo.parentId(),
                startInfo.rawParentId(),
                startInfo.startTimestamp(),
                Instant.now(),
                startInfo.isReplay(),
                startInfo.attempt(),
                succeeded,
                error);
    }
}
