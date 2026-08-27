// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import java.util.concurrent.CompletionStage;

/** Framework callback for an advanced extension CONTEXT primitive. */
@FunctionalInterface
public interface ExtensionContextFunction<T> {
    /** Executes the extension framework logic and asynchronously returns its application result policy. */
    CompletionStage<ExtensionContextResult<T>> apply();
}
