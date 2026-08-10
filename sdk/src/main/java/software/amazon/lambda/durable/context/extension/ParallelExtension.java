// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context.extension;

import java.util.Objects;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.util.ParameterValidator;

/** Canonical implementation of the built-in parallel extension. */
public final class ParallelExtension {
    private ParallelExtension() {}

    public static ParallelDurableFuture execute(ExtensionContext context, String name, ParallelConfig config) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        ParameterValidator.validateOperationName(name);
        return new ParallelExtensionFuture(context, name, config);
    }
}
