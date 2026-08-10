// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;

/** Read-only summary of a direct child operation involved in an extension CONTEXT failure. */
public record ExtensionChildOperationSummary(
        OperationType operationType, String subType, OperationStatus status, ErrorObject error) {}
