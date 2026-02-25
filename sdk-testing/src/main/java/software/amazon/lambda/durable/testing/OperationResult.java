// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationStatus;

/** The operation status and result/error from Step, Context, Callback and ChainedInvoke operations */
public record OperationResult(OperationStatus operationStatus, String result, ErrorObject error) {}
