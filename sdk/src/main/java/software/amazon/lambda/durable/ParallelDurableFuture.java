// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.util.function.Function;
import software.amazon.lambda.durable.model.ParallelResult;

/** User-facing context for managing parallel branch execution within a durable function. */
public interface ParallelDurableFuture extends AutoCloseable, DurableFuture<ParallelResult> {

    <T> DurableFuture<T> branch(String name, Class<T> resultType, Function<DurableContext, T> func);

    <T> DurableFuture<T> branch(String name, TypeToken<T> resultType, Function<DurableContext, T> func);

    void close();
}
