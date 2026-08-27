// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.internal.PrimitiveOperationIdentifier;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.operation.BaseDurableOperation;

/** Utility methods for converting SDK internal types to plugin info records. */
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
        return toOperationInfo(operation, PrimitiveOperationIdentifier.from(identifier), parentId);
    }

    public static OperationInfo toOperationInfo(
            Operation operation, PrimitiveOperationIdentifier identifier, String parentId) {
        return new OperationInfo(
                identifier.operationId(),
                identifier.name(),
                identifier.operationType().toString(),
                identifier.subType(),
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
        return toOperationEndInfo(operation, PrimitiveOperationIdentifier.from(identifier), parentId, isReplay, error);
    }

    public static OperationEndInfo toOperationEndInfo(
            Operation operation,
            PrimitiveOperationIdentifier identifier,
            String parentId,
            boolean isReplay,
            Throwable error) {
        return new OperationEndInfo(
                identifier.operationId(),
                identifier.name(),
                identifier.operationType().toString(),
                identifier.subType(),
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
                error,
                extractResult(operation));
    }

    /**
     * Extracts the serialized result from an operation based on its type. Returns null if the operation has no result
     * (e.g., failed or still running).
     *
     * <p>Only a SUCCEEDED operation reports a result. The status guard is required, not just defensive: a
     * wait-for-condition is checkpointed as {@link OperationType#STEP} and reuses {@code stepDetails().result()} to
     * carry its intermediate check-loop state between attempts, so a failed one can still hold state that must not be
     * surfaced as that operation's result.
     */
    private static String extractResult(Operation operation) {
        if (operation == null || operation.type() == null || operation.status() != OperationStatus.SUCCEEDED) {
            return null;
        }
        return switch (operation.type()) {
            case STEP ->
                operation.stepDetails() != null ? operation.stepDetails().result() : null;
            case CHAINED_INVOKE ->
                operation.chainedInvokeDetails() != null
                        ? operation.chainedInvokeDetails().result()
                        : null;
            case CALLBACK ->
                operation.callbackDetails() != null
                        ? operation.callbackDetails().result()
                        : null;
            case CONTEXT ->
                operation.contextDetails() != null ? operation.contextDetails().result() : null;
            default -> null;
        };
    }

    /**
     * Creates a {@link UserFunctionStartInfo} for when a user function starts executing.
     *
     * @param identifier the operation identifier containing id, name, type, and subType
     * @param parentId the parent operation ID (may be null)
     * @param isReplay true if this operation was present in the checkpointed state delivered at invocation start
     * @param attempt the 1-based attempt number (null for context operations)
     * @return a UserFunctionStartInfo record
     */
    public static UserFunctionStartInfo toUserFunctionStartInfo(
            OperationIdentifier identifier, String parentId, boolean isReplay, Integer attempt) {
        return toUserFunctionStartInfo(PrimitiveOperationIdentifier.from(identifier), parentId, isReplay, attempt);
    }

    public static UserFunctionStartInfo toUserFunctionStartInfo(
            PrimitiveOperationIdentifier identifier, String parentId, boolean isReplay, Integer attempt) {
        return new UserFunctionStartInfo(
                identifier.operationId(),
                identifier.name(),
                identifier.operationType().toString(),
                identifier.subType(),
                parentId,
                Instant.now(),
                isReplay,
                attempt);
    }

    /**
     * Creates a {@link UserFunctionEndInfo} from a start info and outcome.
     *
     * @param startInfo the start info from when the function began
     * @param outcome the user function outcome
     * @param error the error if the function failed or exited incompletely (may be null)
     * @return a UserFunctionEndInfo record
     */
    public static UserFunctionEndInfo toUserFunctionEndInfo(
            UserFunctionStartInfo startInfo, UserFunctionOutcome outcome, Throwable error) {
        return new UserFunctionEndInfo(
                startInfo.id(),
                startInfo.name(),
                startInfo.type(),
                startInfo.subType(),
                startInfo.parentId(),
                startInfo.startTimestamp(),
                Instant.now(),
                startInfo.isReplay(),
                startInfo.attempt(),
                outcome,
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
     * @param initialOperationIds ids of the operations delivered in this invocation's initial state, used to populate
     *     each item's {@code isReplay} indicator
     * @return an OperationChangeInfo record
     */
    public static OperationChangeInfo toOperationChangeInfo(
            String requestId,
            String durableExecutionArn,
            Collection<Operation> updatedOperations,
            Collection<Operation> allOperations,
            Set<String> initialOperationIds) {
        return new OperationChangeInfo(
                requestId,
                durableExecutionArn,
                toOperationItemMap(updatedOperations, initialOperationIds),
                toOperationItemMap(allOperations, initialOperationIds));
    }

    /**
     * Converts durable operations to an unmodifiable map of {@link OperationChangeItemInfo}, keyed by operation ID.
     *
     * @param operations the durable operations to convert
     * @param initialOperationIds ids of the operations delivered in this invocation's initial state, used to populate
     *     each item's {@code isReplay} indicator
     * @return an unmodifiable map of operation ID to item info
     */
    public static Map<String, OperationChangeItemInfo> toOperationItemMap(
            Collection<Operation> operations, Set<String> initialOperationIds) {
        return operations.stream()
                .collect(Collectors.toUnmodifiableMap(
                        Operation::id,
                        operation ->
                                toOperationChangeItemInfo(operation, initialOperationIds.contains(operation.id()))));
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
                BaseDurableOperation.extractErrorFromOperation(operation),
                extractResult(operation));
    }
}
