// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.model.OperationSubType;

/**
 * Describes the durable payload currently being processed by a {@link SerDesStage} or {@link BinarySerDesStage}.
 *
 * <p>The SDK passes this context explicitly only to pipeline stages. Root {@link SerDes} value codecs remain
 * context-free. During serialization, {@link #originalValue()} contains the object supplied to the root value codec;
 * during deserialization it is {@code null}. Stages must treat the original value as read-only.
 */
public record SerDesContext(
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

    /** Creates context for a root execution payload. */
    public static SerDesContext forExecution(
            String durableExecutionArn,
            String executionOperationId,
            String executionOperationName,
            SerDesPayloadKind payloadKind) {
        return new SerDesContext(
                durableExecutionArn,
                "execution/" + executionOperationId + "/" + payloadKind.getEntitySuffix(),
                payloadKind,
                executionOperationId,
                executionOperationName,
                null,
                OperationType.EXECUTION,
                null,
                null,
                null);
    }

    /** Creates context for an operation payload. */
    public static SerDesContext forOperation(
            String durableExecutionArn,
            String operationId,
            String operationName,
            String parentId,
            OperationType operationType,
            OperationSubType operationSubType,
            SerDesPayloadKind payloadKind,
            Integer attempt) {
        var entityId = "operation/" + operationId + "/" + payloadKind.getEntitySuffix();
        if (attempt != null) {
            entityId += "/attempt-" + attempt;
        }
        return new SerDesContext(
                durableExecutionArn,
                entityId,
                payloadKind,
                operationId,
                operationName,
                parentId,
                operationType,
                operationSubType,
                attempt,
                null);
    }

    SerDesContext withOriginalValue(Object originalValue) {
        if (this.originalValue == originalValue) {
            return this;
        }
        return new SerDesContext(
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
