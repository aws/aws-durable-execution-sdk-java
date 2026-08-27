// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * A callback reservation with an immediately available callback ID and an asynchronously completed result.
 *
 * @param <T> the callback result type
 */
public record ExtensionCallback<T>(String callbackId, CompletionStage<T> result) {
    public ExtensionCallback {
        Objects.requireNonNull(callbackId, "callbackId cannot be null");
        Objects.requireNonNull(result, "result cannot be null");
    }
}
