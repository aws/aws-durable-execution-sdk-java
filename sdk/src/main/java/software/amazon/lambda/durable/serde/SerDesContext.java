// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.model.OperationSubType;

/**
 * Describes the durable payload currently being processed by a {@link SerDes}.
 *
 * <p>The SDK sets this context only while invoking a configured SerDes. Direct customer calls to SerDes methods do not
 * have a current context.
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
        Integer attempt) {

    /** Returns the context for the SerDes call on the current thread, or {@code null} outside SDK-managed calls. */
    public static SerDesContext getCurrentContext() {
        return SerDesContextHolder.get();
    }

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
        return new SerDesContext(
                durableExecutionArn,
                "operation/" + operationId + "/" + payloadKind.getEntitySuffix(),
                payloadKind,
                operationId,
                operationName,
                parentId,
                operationType,
                operationSubType,
                attempt);
    }
}
