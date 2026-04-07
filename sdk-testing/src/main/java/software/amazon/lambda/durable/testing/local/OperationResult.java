// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing.local;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationStatus;

/** The operation status and result/error from Step, Context, Callback and ChainedInvoke operations */
public record OperationResult(OperationStatus operationStatus, String result, ErrorObject error) {
    public static OperationResult succeeded(String result) {
        return new OperationResult(OperationStatus.SUCCEEDED, result, null);
    }

    public static OperationResult failed(ErrorObject error) {
        return new OperationResult(OperationStatus.FAILED, null, error);
    }

    public static OperationResult stopped(ErrorObject error) {
        return new OperationResult(OperationStatus.STOPPED, null, error);
    }

    public static OperationResult timedout() {
        return new OperationResult(OperationStatus.TIMED_OUT, null, null);
    }
}
