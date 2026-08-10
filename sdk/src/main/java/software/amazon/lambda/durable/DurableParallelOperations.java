// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import software.amazon.lambda.durable.config.ParallelConfig;

/** Context-free static facades for durable parallel operations. */
public final class DurableParallelOperations {
    private DurableParallelOperations() {}

    public static ParallelDurableFuture parallel(String name) {
        return DurableContext.getCurrentContext().parallel(name);
    }

    public static ParallelDurableFuture parallel(String name, ParallelConfig config) {
        return DurableContext.getCurrentContext().parallel(name, config);
    }
}
