// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.util.Objects;
import software.amazon.lambda.durable.model.SafeCloseable;

final class OperationContextStorage<T> {
    private final String contextName;
    private final ThreadLocal<T> current = new ThreadLocal<>();

    OperationContextStorage(String contextName) {
        this.contextName = contextName;
    }

    T getCurrentContext() {
        var context = current.get();
        if (context == null) {
            throw new IllegalStateException(contextName + " is not active on the current thread");
        }
        return context;
    }

    SafeCloseable attach(T context) {
        Objects.requireNonNull(context, "context cannot be null");
        var previous = current.get();
        current.set(context);
        return () -> {
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        };
    }
}
