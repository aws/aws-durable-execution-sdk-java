// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.util.Objects;

/**
 * Identifies the durable payload currently being processed by a {@link SerDes}.
 *
 * <p>The SDK passes this context explicitly to the context-aware default methods on {@link SerDes}. Existing
 * implementations remain compatible because those methods delegate to the original context-free methods by default.
 *
 * @param durableExecutionArn ARN of the durable execution
 * @param entityId stable identifier of the execution or operation payload
 */
public record SerDesContext(String durableExecutionArn, String entityId) {
    public SerDesContext {
        Objects.requireNonNull(durableExecutionArn, "durableExecutionArn cannot be null");
        Objects.requireNonNull(entityId, "entityId cannot be null");
    }
}
