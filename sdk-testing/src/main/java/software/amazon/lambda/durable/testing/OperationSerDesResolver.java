// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.lambda.durable.serde.SerDes;

/** Resolves the SerDes used to inspect a durable operation's persisted result. */
@FunctionalInterface
public interface OperationSerDesResolver {
    /** Uses the runner-wide SerDes for every operation. */
    OperationSerDesResolver DEFAULT = (operation, defaultSerDes) -> defaultSerDes;

    /**
     * Resolves the effective SerDes for an operation.
     *
     * @param operation operation being inspected
     * @param defaultSerDes runner-wide SerDes
     * @return the SerDes that encoded this operation's result
     */
    SerDes resolve(Operation operation, SerDes defaultSerDes);
}
