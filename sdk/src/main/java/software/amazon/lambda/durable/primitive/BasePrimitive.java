// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.primitive;

import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.internal.PrimitiveOperationIdentifier;
import software.amazon.lambda.durable.operation.BaseDurableOperation;

/** Internal base class for extension-backed durable primitives. */
public abstract class BasePrimitive extends BaseDurableOperation {
    protected final BasePrimitive parentOperation;

    protected BasePrimitive(
            PrimitiveOperationIdentifier operationIdentifier,
            DurableContextImpl durableContext,
            BasePrimitive parentOperation) {
        super(operationIdentifier, durableContext, parentOperation);
        this.parentOperation = parentOperation;
    }

    protected BasePrimitive(
            PrimitiveOperationIdentifier operationIdentifier,
            DurableContextImpl durableContext,
            BasePrimitive parentOperation,
            boolean isVirtual) {
        super(operationIdentifier, durableContext, parentOperation, isVirtual);
        this.parentOperation = parentOperation;
    }

    @Override
    protected boolean isOperationCompleted() {
        return super.isOperationCompleted();
    }

    @Override
    protected Operation getOperation() {
        return super.getOperation();
    }
}
