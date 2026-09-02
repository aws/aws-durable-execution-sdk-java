// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload;

import java.util.Objects;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;

/**
 * Stable identity and operation metadata for a payload being stored or loaded.
 *
 * @param durableExecutionArn durable execution ARN
 * @param entityId stable identifier for this payload within the durable execution
 * @param payloadKind role of the payload
 * @param operationId operation identifier, or the execution operation identifier for root payloads
 * @param operationName operation name, when present
 * @param parentId parent context identifier, when present
 * @param operationType durable operation type
 * @param operationSubType durable operation subtype, or null for root execution payloads
 * @param attempt current attempt for retryable operations, when available
 * @param originalValue original object supplied to SerDes during serialization, or null during loading
 */
public record PayloadOffloadContext(
        String durableExecutionArn,
        String entityId,
        SerDesPayloadKind payloadKind,
        String operationId,
        String operationName,
        String parentId,
        OperationType operationType,
        OperationSubType operationSubType,
        Integer attempt,
        Object originalValue) {

    public PayloadOffloadContext {
        Objects.requireNonNull(durableExecutionArn, "durableExecutionArn cannot be null");
        Objects.requireNonNull(entityId, "entityId cannot be null");
        Objects.requireNonNull(payloadKind, "payloadKind cannot be null");
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(operationType, "operationType cannot be null");
    }

    /** Creates context for a root execution input, output, or exception payload. */
    public static PayloadOffloadContext forExecution(
            String durableExecutionArn, String executionOperationId, String executionName, SerDesPayloadKind kind) {
        return new PayloadOffloadContext(
                durableExecutionArn,
                "execution/" + executionOperationId + "/" + kind.entitySuffix(),
                kind,
                executionOperationId,
                executionName,
                null,
                OperationType.EXECUTION,
                null,
                null,
                null);
    }

    /** Creates context for a durable operation payload. */
    public static PayloadOffloadContext forOperation(
            String durableExecutionArn,
            OperationIdentifier operation,
            String parentId,
            SerDesPayloadKind kind,
            Integer attempt) {
        var entityId = "operation/" + operation.operationId() + "/" + kind.entitySuffix();
        if (attempt != null) {
            entityId += "/attempt-" + attempt;
        }
        return new PayloadOffloadContext(
                durableExecutionArn,
                entityId,
                kind,
                operation.operationId(),
                operation.name(),
                parentId,
                operation.operationType(),
                operation.subType(),
                attempt,
                null);
    }

    /** Returns this context with the original value available to preview generators. */
    public PayloadOffloadContext withOriginalValue(Object originalValue) {
        if (this.originalValue == originalValue) {
            return this;
        }
        return new PayloadOffloadContext(
                durableExecutionArn,
                entityId,
                payloadKind,
                operationId,
                operationName,
                parentId,
                operationType,
                operationSubType,
                attempt,
                originalValue);
    }
}
