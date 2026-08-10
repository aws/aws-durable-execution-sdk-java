// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context.extension;

import static software.amazon.lambda.durable.config.NestingType.FLAT;
import static software.amazon.lambda.durable.context.extension.ExtensionConcurrencyCoordinator.ItemStatus.FAILED;
import static software.amazon.lambda.durable.context.extension.ExtensionConcurrencyCoordinator.ItemStatus.SKIPPED;
import static software.amazon.lambda.durable.model.OperationSubType.MAP;
import static software.amazon.lambda.durable.model.OperationSubType.MAP_ITERATION;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextReplayContext;
import software.amazon.lambda.durable.extension.ExtensionContextResult;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.util.ExceptionHelper;
import software.amazon.lambda.durable.util.ParameterValidator;

/** Canonical implementation of the built-in map extension. */
public final class MapExtension {
    private static final int LARGE_RESULT_THRESHOLD = 256 * 1024;

    private MapExtension() {}

    public static <I, O> DurableFuture<MapResult<O>> execute(
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
        return parent.runInChildContextAsync(
                MAP.getValue(),
                mapResultType(),
                () -> executeInChildContext(
                        name, itemList, iterationNames, resultType, function, mapConfig, virtualEmptyMap),
                parentConfig(mapConfig, virtualEmptyMap));
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
        var replayState = replay.isReplayingChildren() ? replay.getReplayState() : null;
        if (replay.isReplayingChildren() && replayState == null) {
            throw new IllegalStateException("Missing result in completed Map operation");
        }

        var coordinator = new ExtensionConcurrencyCoordinator(config.maxConcurrency(), config.completionConfig());
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

    private static <I, O> List<ExtensionConcurrencyCoordinator.Item<O>> registerItems(
            ExtensionConcurrencyCoordinator coordinator,
            List<I> items,
            List<String> iterationNames,
            TypeToken<O> resultType,
            DurableContext.MapFunction<I, O> function,
            MapConfig config,
            MapResult<O> replayState) {
        var context = ExtensionContext.getCurrentContext();
        var registeredItems = new ArrayList<ExtensionConcurrencyCoordinator.Item<O>>(items.size());
        var iterationConfig = RunInChildContextConfig.builder()
                .serDes(config.serDes())
                .isVirtual(config.nestingType() == FLAT)
                .build();

        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var itemIndex = index;
            var reservation = context.reserve(iterationNames.get(index));
            var skipped = replayState != null
                    && replayState.getItem(index).status() == MapResult.MapResultItem.Status.SKIPPED;
            registeredItems.add(coordinator.register(
                    () -> reservation.runInChildContextAsync(
                            MAP_ITERATION.getValue(),
                            resultType,
                            () -> function.apply(item, itemIndex, DurableContext.getCurrentContext()),
                            iterationConfig),
                    skipped));
        }
        return registeredItems;
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

    private static ExtensionConcurrencyCoordinator.ExpectedCompletionStatus expectedCompletion(
            MapResult<?> replayState) {
        return new ExtensionConcurrencyCoordinator.ExpectedCompletionStatus(
                replayState.succeeded().size() + replayState.failed().size(),
                CompletionConfig.CompletionDecision.complete(replayState.completionReason()));
    }

    private static <O> MapResult<O> constructResult(
            List<ExtensionConcurrencyCoordinator.Item<O>> items,
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

    private static <O> MapResult.MapResultItem<O> failedResult(ExtensionConcurrencyCoordinator.Item<O> item) {
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

    private static ExtensionContextConfig parentConfig(MapConfig config, boolean virtualEmptyMap) {
        return ExtensionContextConfig.builder()
                .childContextConfig(RunInChildContextConfig.builder()
                        .serDes(config.serDes())
                        .isVirtual(virtualEmptyMap)
                        .build())
                .emitUserFunctionEvents(false)
                .suppressLateChildCheckpoints(true)
                .build();
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
}
