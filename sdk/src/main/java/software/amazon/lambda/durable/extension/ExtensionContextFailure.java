// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import java.util.List;
import software.amazon.awssdk.services.lambda.model.ErrorObject;

/** Read-only failure information supplied to an extension CONTEXT error handler. */
public final class ExtensionContextFailure {
    private final String contextName;
    private final String subType;
    private final Throwable originalException;
    private final ErrorObject error;
    private final List<ExtensionChildOperationSummary> childOperations;

    public ExtensionContextFailure(
            String contextName,
            String subType,
            Throwable originalException,
            ErrorObject error,
            List<ExtensionChildOperationSummary> childOperations) {
        this.contextName = contextName;
        this.subType = subType;
        this.originalException = originalException;
        this.error = error;
        this.childOperations = List.copyOf(childOperations);
    }

    public String contextName() {
        return contextName;
    }

    public String subType() {
        return subType;
    }

    public Throwable originalException() {
        return originalException;
    }

    public ErrorObject error() {
        return error;
    }

    public List<ExtensionChildOperationSummary> childOperations() {
        return childOperations;
    }
}
