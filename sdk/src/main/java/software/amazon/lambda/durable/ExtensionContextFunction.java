// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

/** Framework callback for an advanced extension CONTEXT primitive. */
@FunctionalInterface
public interface ExtensionContextFunction<T> {
    /** Executes the extension framework logic and returns its application result policy. */
    ExtensionContextResult<T> apply();
}
