// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import com.amazonaws.services.lambda.runtime.Context;
import java.util.Objects;
import org.slf4j.Logger;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.logging.DurableLogger;

/** Supplies one pre-reserved DAG operation to an existing durable operation facade. */
final class ReservedOperationContext implements ExtensionContext {
    private final ExtensionContext delegate;
    private final String operationName;
    private final ExtensionOperation operation;
    private boolean reserved;

    ReservedOperationContext(ExtensionContext delegate, String operationName, ExtensionOperation operation) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.operationName = Objects.requireNonNull(operationName, "operationName cannot be null");
        this.operation = Objects.requireNonNull(operation, "operation cannot be null");
    }

    @Override
    public boolean isReplaying() {
        return delegate.isReplaying();
    }

    @Override
    public synchronized ExtensionOperation reserve(String name) {
        if (!reserved) {
            if (!operationName.equals(name)) {
                throw new IllegalStateException(
                        "Expected operation '" + operationName + "' to be reserved before '" + name + "'");
            }
            reserved = true;
            return operation;
        }
        return delegate.reserve(name);
    }

    @Override
    public ExtensionOperation reserve(String name, String localOperationId) {
        return delegate.reserve(name, localOperationId);
    }

    @Override
    public DurableLogger getLogger() {
        return delegate.getLogger();
    }

    @Override
    public DurableLogger getLogger(Logger logger) {
        return delegate.getLogger(logger);
    }

    @Override
    public Context getLambdaContext() {
        return delegate.getLambdaContext();
    }

    @Override
    public String getExecutionArn() {
        return delegate.getExecutionArn();
    }

    @Override
    public DurableConfig getDurableConfig() {
        return delegate.getDurableConfig();
    }

    @Override
    public String getContextId() {
        return delegate.getContextId();
    }

    @Override
    public String getContextName() {
        return delegate.getContextName();
    }
}
