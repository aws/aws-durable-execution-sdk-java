// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.context.extension.ParallelExtension;
import software.amazon.lambda.durable.extension.ExtensionContext;

/** Context-free static facades for durable parallel operations. */
public final class DurableParallelOperations {
    private DurableParallelOperations() {}

    public static ParallelDurableFuture parallel(String name) {
        return parallel(name, ParallelConfig.builder().build());
    }

    public static ParallelDurableFuture parallel(String name, ParallelConfig config) {
        return ParallelExtension.execute(ExtensionContext.getCurrentContext(), name, config);
    }
}
