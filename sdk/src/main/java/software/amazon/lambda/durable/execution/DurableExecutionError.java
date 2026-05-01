// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

/**
 * A throwable error that can be used internally by Durable Execution SDK. Users of the SDK should not throw this error
 * directly or catch this error in their code.
 */
public class DurableExecutionError extends Error {
    public DurableExecutionError(String message) {
        super(message);
    }
}
