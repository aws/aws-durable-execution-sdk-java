// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

/**
 * Status of a Lambda invocation at the end of its execution.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public enum InvocationStatus {
    /** Execution completed successfully in this invocation. */
    SUCCEEDED,
    /** Execution failed in this invocation. */
    FAILED,
    /** Execution suspended — will resume in a future invocation. */
    PENDING
}
