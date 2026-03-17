// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

public interface StepContext extends BaseContext {
    /** Returns the current retry attempt number (0-based). */
    int getAttempt();
}
