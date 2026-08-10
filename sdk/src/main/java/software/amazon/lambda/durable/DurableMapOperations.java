// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.model.MapResult;

/** Context-free static facades for durable map operations. */
public final class DurableMapOperations {
    private DurableMapOperations() {}

    public static <I, O> MapResult<O> map(
            String name, Collection<I> items, Class<O> resultType, Function<I, O> function) {
        return currentContext().map(name, items, resultType, adapt(function));
    }

    public static <I, O> MapResult<O> map(
            String name, Collection<I> items, TypeToken<O> resultType, Function<I, O> function) {
        return currentContext().map(name, items, resultType, adapt(function));
    }

    public static <I, O> MapResult<O> map(
            String name,
            Collection<I> items,
            Class<O> resultType,
            Function<I, O> function,
            MapConfig config) {
        return currentContext().map(name, items, resultType, adapt(function), config);
    }

    public static <I, O> MapResult<O> map(
            String name,
            Collection<I> items,
            TypeToken<O> resultType,
            Function<I, O> function,
            MapConfig config) {
        return currentContext().map(name, items, resultType, adapt(function), config);
    }

    public static <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, Class<O> resultType, Function<I, O> function) {
        return currentContext().mapAsync(name, items, resultType, adapt(function));
    }

    public static <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, TypeToken<O> resultType, Function<I, O> function) {
        return currentContext().mapAsync(name, items, resultType, adapt(function));
    }

    public static <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name,
            Collection<I> items,
            Class<O> resultType,
            Function<I, O> function,
            MapConfig config) {
        return currentContext().mapAsync(name, items, resultType, adapt(function), config);
    }

    public static <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name,
            Collection<I> items,
            TypeToken<O> resultType,
            Function<I, O> function,
            MapConfig config) {
        return currentContext().mapAsync(name, items, resultType, adapt(function), config);
    }

    private static <I, O> DurableContext.MapFunction<I, O> adapt(Function<I, O> function) {
        Objects.requireNonNull(function, "function cannot be null");
        return (item, index, ignored) -> {
            try (var scope = MapItemContext.attach(index)) {
                return function.apply(item);
            }
        };
    }

    private static DurableContext currentContext() {
        return DurableContext.getCurrentContext();
    }
}
