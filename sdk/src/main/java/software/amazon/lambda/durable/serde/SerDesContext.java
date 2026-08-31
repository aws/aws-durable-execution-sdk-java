// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Identifies the durable payload currently being processed by a {@link SerDes}.
 *
 * <p>The SDK exposes this context through thread-local storage so the existing {@link SerDes} interface remains
 * backward compatible. The context is available only while the SDK is invoking a SerDes method.
 *
 * @param durableExecutionArn ARN of the durable execution
 * @param entityId stable identifier of the execution or operation payload
 */
public record SerDesContext(String durableExecutionArn, String entityId) {
    private static final ThreadLocal<SerDesContext> CURRENT = new ThreadLocal<>();

    public SerDesContext {
        Objects.requireNonNull(durableExecutionArn, "durableExecutionArn cannot be null");
        Objects.requireNonNull(entityId, "entityId cannot be null");
    }

    /**
     * Returns the context for the current SDK-managed SerDes call.
     *
     * @return the current context, or {@code null} when called outside an SDK-managed SerDes call
     */
    public static SerDesContext getCurrentContext() {
        return CURRENT.get();
    }

    static <T> T callWithContext(SerDesContext context, Supplier<T> action) {
        var previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(context, "context cannot be null"));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
