// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.util.function.Function;

public interface DurableParallelFuture extends DurableFuture<ParallelResult> {
    <T> DurableFuture<T> branch(
            String name, TypeToken<T> resultType, Function<DurableContext, T> func, ParallelBranchConfig config);
}
