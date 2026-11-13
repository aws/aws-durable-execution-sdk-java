// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

/** Terminal status of a distributed map run. */
public enum DistributedMapStatus {
    SUCCEEDED,
    FAILED,
    STOPPED,
    TIMED_OUT
}
