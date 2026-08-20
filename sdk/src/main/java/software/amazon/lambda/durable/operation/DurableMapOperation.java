// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static software.amazon.lambda.durable.model.OperationSubType.MAP;
import static software.amazon.lambda.durable.model.OperationSubType.MAP_ITERATION;
import static software.amazon.lambda.durable.operation.DurableConcurrencyOperation.OperationConcurrencyCoordinator.ItemStatus.FAILED;
import static software.amazon.lambda.durable.operation.DurableConcurrencyOperation.OperationConcurrencyCoordinator.ItemStatus.SKIPPED;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.MapIterationFailedException;
import software.amazon.lambda.durable.exception.NonDeterministicExecutionException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextReplayContext;
import software.amazon.lambda.durable.extension.ExtensionContextResult;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.model.SafeCloseable;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;
import software.amazon.lambda.durable.util.ParameterValidator;

/** Context-free static facade and canonical implementation of durable MAP operations. */
public final class DurableMapOperation extends DurableConcurrencyOperation {
    private DurableMapOperation() {}

    public static <I, O> MapResult<O> map(
            String name, Collection<I> items, Class<O> resultType, Function<I, O> function) {
        return mapAsync(name, items, resultType, function).get();
    }

    public static <I, O> MapResult<O> map(
            String name, Collection<I> items, TypeToken<O> resultType, Function<I, O> function) {
        return mapAsync(name, items, resultType, function).get();
    }

    public static <I, O> MapResult<O> map(
            String name, Collection<I> items, Class<O> resultType, Function<I, O> function, MapConfig config) {
        return mapAsync(name, items, resultType, function, config).get();
    }

    public static <I, O> MapResult<O> map(
            String name, Collection<I> items, TypeToken<O> resultType, Function<I, O> function, MapConfig config) {
        return mapAsync(name, items, resultType, function, config).get();
    }

    public static <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, Class<O> resultType, Function<I, O> function) {
        return mapAsync(name, items, TypeToken.get(resultType), function);
    }

    public static <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, TypeToken<O> resultType, Function<I, O> function) {
        return mapAsync(name, items, resultType, function, MapConfig.builder().build());
    }

    public static <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, Class<O> resultType, Function<I, O> function, MapConfig config) {
        return mapAsync(name, items, TypeToken.get(resultType), function, config);
    }

    public static <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, TypeToken<O> resultType, Function<I, O> function, MapConfig config) {
        return mapAsync(ExtensionContext.getCurrentContext(), name, items, resultType, adapt(function), config);
    }

    public static <I, O> DurableFuture<MapResult<O>> mapAsync(
            ExtensionContext context,
            String name,
            Collection<I> items,
            TypeToken<O> resultType,
            DurableContext.MapFunction<I, O> function,
            MapConfig config) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(items, "items cannot be null");
        Objects.requireNonNull(function, "function cannot be null");
        Objects.requireNonNull(resultType, "resultType cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        ParameterValidator.validateOperationName(name);
        ParameterValidator.validateOrderedCollection(items);

        if (config.serDes() == null) {
            config = config.toBuilder()
                    .serDes(context.getDurableConfig().getSerDes())
                    .build();
        }
        var itemList = List.copyOf(items);
        var iterationNames = resolveIterationNames(name, itemList, config);
        var parent = context.reserve(name);
        validateMinSuccessful(itemList, config);

        var mapConfig = config;
        var virtualEmptyMap = itemList.isEmpty() && !context.getDurableConfig().shouldCheckpointEmptyMap();
        var parentConfig = parentContextConfig(mapConfig.serDes(), virtualEmptyMap).toBuilder()
                .validateCompletedReplay(true)
                .build();
        return parent.runInChildContextAsync(
                MAP.getValue(),
                mapResultType(),
                () -> executeInChildContext(
                        name, itemList, iterationNames, resultType, function, mapConfig, virtualEmptyMap),
                parentConfig);
    }

    private static <I, O> DurableContext.MapFunction<I, O> adapt(Function<I, O> function) {
        Objects.requireNonNull(function, "function cannot be null");
        return (item, index, ignored) -> {
            try (var scope = MapItemContext.attach(index)) {
                return function.apply(item);
            }
        };
    }

    private static <I, O> ExtensionContextResult<MapResult<O>> executeInChildContext(
            String name,
            List<I> items,
            List<String> iterationNames,
            TypeToken<O> resultType,
            DurableContext.MapFunction<I, O> function,
            MapConfig config,
            boolean virtualEmptyMap) {
        if (virtualEmptyMap) {
            ExtensionContext.getCurrentContext()
                    .getLogger()
                    .warn(
                            "Empty map operation '{}' is not checkpointed by default. This behavior is unintended and"
                                    + " may affect replay and plugin instrumentation. Enable"
                                    + " DurableConfig.withCheckpointEmptyMap(true) to checkpoint empty maps.",
                            name);
            return ExtensionContextResult.completed(MapResult.empty());
        }

        var replay = ExtensionContextReplayContext.<MapResult<O>>getCurrentContext();
        var replayingCompletedMap = replay.isReplayingChildren() || replay.isValidatingReplay();
        var replayState = replayingCompletedMap ? replay.getReplayState() : null;
        if (replayingCompletedMap && replayState == null) {
            throw new IllegalStateException("Missing result in completed Map operation");
        }
        if (replayState != null) {
            validateReplayCardinality(name, items, replayState);
        }
        if (replay.isValidatingReplay()) {
            return validateCompletedReplay(name, items, iterationNames, resultType, function, config, replayState);
        }

        var coordinator = new OperationConcurrencyCoordinator(config.maxConcurrency(), config.completionConfig());
        var registeredItems =
                registerItems(coordinator, items, iterationNames, resultType, function, config, replayState);
        coordinator.closeRegistration();
        var completion = replayState == null
                ? coordinator.awaitCompletion()
                : coordinator.awaitCompletion(expectedCompletion(replayState));
        var result = constructResult(registeredItems, completion.completionDecision());
        var strippedResult = stripMapResult(result);
        return config.itemNamer() == null
                ? ExtensionContextResult.replayChildrenAboveSize(result, strippedResult, LARGE_RESULT_THRESHOLD)
                : ExtensionContextResult.replayChildren(result, strippedResult);
    }

    private static <I, O> ExtensionContextResult<MapResult<O>> validateCompletedReplay(
            String name,
            List<I> items,
            List<String> iterationNames,
            TypeToken<O> resultType,
            DurableContext.MapFunction<I, O> function,
            MapConfig config,
            MapResult<O> replayState) {
        if (config.nestingType() == NestingType.NESTED) {
            validateNestedIterations(items, iterationNames, resultType, function, config, replayState);
        }
        return ExtensionContextResult.completed(replayState);
    }

    private static void validateReplayCardinality(String name, List<?> items, MapResult<?> replayState) {
        if (items.size() != replayState.size()) {
            throw new NonDeterministicExecutionException(String.format(
                    "Map item count mismatch for \"%s\". Expected %d, got %d", name, replayState.size(), items.size()));
        }
    }

    private static <I, O> void validateNestedIterations(
            List<I> items,
            List<String> iterationNames,
            TypeToken<O> resultType,
            DurableContext.MapFunction<I, O> function,
            MapConfig config,
            MapResult<O> replayState) {
        var context = ExtensionContext.getCurrentContext();
        var iterationConfig = childContextConfig(
                config.serDes(), config.nestingType(), failure -> new MapIterationFailedException(failure.operation()));
        for (int index = 0; index < items.size(); index++) {
            var reservation = context.reserve(iterationNames.get(index));
            if (replayState.getItem(index).status() == MapResult.MapResultItem.Status.SKIPPED) {
                continue;
            }
            launchIteration(reservation, items.get(index), index, resultType, function, iterationConfig);
        }
    }

    private static <I, O> List<OperationConcurrencyCoordinator.Item<O>> registerItems(
            OperationConcurrencyCoordinator coordinator,
            List<I> items,
            List<String> iterationNames,
            TypeToken<O> resultType,
            DurableContext.MapFunction<I, O> function,
            MapConfig config,
            MapResult<O> replayState) {
        var context = ExtensionContext.getCurrentContext();
        var registeredItems = new ArrayList<OperationConcurrencyCoordinator.Item<O>>(items.size());
        var iterationConfig = childContextConfig(
                config.serDes(), config.nestingType(), failure -> new MapIterationFailedException(failure.operation()));

        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var itemIndex = index;
            var reservation = context.reserve(iterationNames.get(index));
            var skipped = replayState != null
                    && replayState.getItem(index).status() == MapResult.MapResultItem.Status.SKIPPED;
            registeredItems.add(coordinator.register(
                    () -> launchIteration(reservation, item, itemIndex, resultType, function, iterationConfig),
                    skipped));
        }
        return registeredItems;
    }

    private static <I, O> DurableFuture<O> launchIteration(
            ExtensionOperation reservation,
            I item,
            int index,
            TypeToken<O> resultType,
            DurableContext.MapFunction<I, O> function,
            ExtensionContextConfig config) {
        return reservation.runInChildContextAsync(
                MAP_ITERATION.getValue(),
                resultType,
                () -> ExtensionContextResult.replayChildrenAboveSize(
                        function.apply(item, index, DurableContext.requireCurrentContext()),
                        null,
                        LARGE_RESULT_THRESHOLD),
                config);
    }

    private static List<String> resolveIterationNames(String mapName, List<?> items, MapConfig config) {
        var namer = config.itemNamer();
        var prefix = mapName == null ? "map-iteration-" : mapName + "-iteration-";
        var names = new ArrayList<String>(items.size());
        for (int index = 0; index < items.size(); index++) {
            var iterationName = namer == null ? prefix + index : namer.apply(items.get(index), index);
            ParameterValidator.validateOperationName(iterationName);
            names.add(iterationName);
        }
        return names;
    }

    private static OperationConcurrencyCoordinator.ExpectedCompletionStatus expectedCompletion(
            MapResult<?> replayState) {
        return new OperationConcurrencyCoordinator.ExpectedCompletionStatus(
                replayState.succeeded().size() + replayState.failed().size(),
                CompletionConfig.CompletionDecision.complete(replayState.completionReason()));
    }

    private static <O> MapResult<O> constructResult(
            List<OperationConcurrencyCoordinator.Item<O>> items,
            CompletionConfig.CompletionDecision completionDecision) {
        var results = new ArrayList<MapResult.MapResultItem<O>>(Collections.nCopies(items.size(), null));
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            if (item.status() == SKIPPED) {
                results.set(index, MapResult.MapResultItem.skipped());
            } else if (item.status() == FAILED) {
                results.set(index, failedResult(item));
            } else {
                results.set(
                        index, MapResult.MapResultItem.succeeded(item.future().get()));
            }
        }
        return new MapResult<>(results, completionDecision.completionStatus());
    }

    private static <O> MapResult.MapResultItem<O> failedResult(OperationConcurrencyCoordinator.Item<O> item) {
        try {
            item.future().get();
            throw new IllegalStateException("Failed map item completed successfully");
        } catch (SuspendExecutionException | UnrecoverableDurableExecutionException exception) {
            throw exception;
        } catch (Throwable throwable) {
            return MapResult.MapResultItem.failed(
                    MapResult.MapError.of(ExceptionHelper.unwrapCompletableFuture(throwable)));
        }
    }

    private static <O> MapResult<O> stripMapResult(MapResult<O> result) {
        return new MapResult<>(
                result.items().stream()
                        .map(item -> new MapResult.MapResultItem<O>(item.status(), null, null))
                        .toList(),
                result.completionReason());
    }

    private static void validateMinSuccessful(List<?> items, MapConfig config) {
        var completionConfig = config.completionConfig();
        if (!completionConfig.hasCustomShouldComplete()
                && completionConfig.minSuccessful() != null
                && completionConfig.minSuccessful() > items.size()) {
            throw new IllegalArgumentException("minSuccessful cannot be greater than total items: "
                    + completionConfig.minSuccessful() + " > " + items.size());
        }
    }

    private static <O> TypeToken<MapResult<O>> mapResultType() {
        return new TypeToken<>() {};
    }

    /** Metadata for the map item function active on the current SDK-managed thread. */
    public static final class MapItemContext {
        private static final ThreadLocal<MapItemContext> CURRENT = new ThreadLocal<>();

        private final int index;

        private MapItemContext(int index) {
            this.index = index;
        }

        /** Returns the map item context attached to the current SDK-managed thread. */
        public static MapItemContext getCurrentContext() {
            var context = CURRENT.get();
            if (context == null) {
                throw new IllegalStateException("MapItemContext is not active on the current thread");
            }
            return context;
        }

        /** Returns the zero-based index of the current map item. */
        public int getIndex() {
            return index;
        }

        /** Attaches map item metadata for the duration of the returned scope. */
        public static SafeCloseable attach(int index) {
            var previous = CURRENT.get();
            CURRENT.set(new MapItemContext(index));
            return () -> restore(previous);
        }

        private static void restore(MapItemContext previous) {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Configuration for durable MAP operations. */
    public static final class MapConfig {
        private final int maxConcurrency;
        private final CompletionConfig completionConfig;
        private final SerDes serDes;
        private final NestingType nestingType;
        private final BiFunction<Object, Integer, String> itemNamer;

        private MapConfig(Builder builder) {
            maxConcurrency = Objects.requireNonNullElse(builder.maxConcurrency, Integer.MAX_VALUE);
            completionConfig = Objects.requireNonNullElseGet(builder.completionConfig, CompletionConfig::allCompleted);
            serDes = builder.serDes;
            nestingType = Objects.requireNonNullElse(builder.nestingType, NestingType.NESTED);
            itemNamer = builder.itemNamer;
            if (itemNamer != null && nestingType == NestingType.FLAT) {
                throw new IllegalArgumentException("itemNamer is not supported with FLAT map nesting");
            }
        }

        public Integer maxConcurrency() {
            return maxConcurrency;
        }

        public CompletionConfig completionConfig() {
            return completionConfig;
        }

        public SerDes serDes() {
            return serDes;
        }

        public NestingType nestingType() {
            return nestingType;
        }

        public BiFunction<Object, Integer, String> itemNamer() {
            return itemNamer;
        }

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder()
                    .maxConcurrency(maxConcurrency)
                    .completionConfig(completionConfig)
                    .serDes(serDes)
                    .nestingType(nestingType)
                    .itemNamer(itemNamer);
        }

        /** Builder for {@link MapConfig}. */
        public static final class Builder {
            private Integer maxConcurrency;
            private CompletionConfig completionConfig;
            private SerDes serDes;
            private NestingType nestingType;
            private BiFunction<Object, Integer, String> itemNamer;

            private Builder() {}

            public Builder maxConcurrency(Integer maxConcurrency) {
                if (maxConcurrency != null && maxConcurrency < 1) {
                    throw new IllegalArgumentException("maxConcurrency must be at least 1, got: " + maxConcurrency);
                }
                this.maxConcurrency = maxConcurrency;
                return this;
            }

            public Builder completionConfig(CompletionConfig completionConfig) {
                this.completionConfig = completionConfig;
                return this;
            }

            public Builder serDes(SerDes serDes) {
                this.serDes = serDes;
                return this;
            }

            public Builder nestingType(NestingType nestingType) {
                this.nestingType = nestingType;
                return this;
            }

            public Builder itemNamer(BiFunction<Object, Integer, String> itemNamer) {
                this.itemNamer = itemNamer;
                return this;
            }

            public <I> Builder itemNamer(Class<I> itemType, BiFunction<? super I, Integer, String> itemNamer) {
                Objects.requireNonNull(itemType, "itemType cannot be null");
                this.itemNamer =
                        itemNamer == null ? null : (item, index) -> itemNamer.apply(itemType.cast(item), index);
                return this;
            }

            public MapConfig build() {
                return new MapConfig(this);
            }
        }
    }
}
