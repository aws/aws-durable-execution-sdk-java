// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.map;

import static software.amazon.lambda.durable.model.ConcurrencyCompletionStatus.CUSTOM_COMPLETION_FAILED;
import static software.amazon.lambda.durable.model.ConcurrencyCompletionStatus.CUSTOM_COMPLETION_SUCCEEDED;
import static software.amazon.lambda.durable.model.MapResult.MapResultItem.Status.SKIPPED;
import static software.amazon.lambda.durable.operation.DurableConcurrencyOperation.CompletionConfig.CompletionDecision.complete;
import static software.amazon.lambda.durable.operation.DurableConcurrencyOperation.CompletionConfig.CompletionDecision.continueExecution;
import static software.amazon.lambda.durable.operation.DurableMapOperation.map;
import static software.amazon.lambda.durable.operation.DurableStepOperation.step;

import java.util.List;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.operation.DurableConcurrencyOperation.CompletionConfig;
import software.amazon.lambda.durable.operation.DurableMapOperation.MapConfig;
import software.amazon.lambda.durable.operation.DurableMapOperation.MapItemContext;
import software.amazon.lambda.durable.operation.DurableStepOperation.StepConfig;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * Example demonstrating a custom {@code shouldComplete} condition for a map operation.
 *
 * <p>The operation completes successfully once enough providers respond, or completes unsuccessfully once too many
 * providers fail. The custom decision chooses both when the map completes and which completion status the caller sees.
 */
public class CustomShouldCompleteMapExample
        extends DurableHandler<CustomShouldCompleteMapExample.Input, CustomShouldCompleteMapExample.Output> {

    public record Input(List<String> providers, int requiredSuccesses, int failureLimit) {
        public Input {
            providers = providers == null
                    ? List.of("primary", "secondary", "bad-cache", "tertiary")
                    : List.copyOf(providers);
            requiredSuccesses = requiredSuccesses > 0 ? requiredSuccesses : 2;
            failureLimit = failureLimit > 0 ? failureLimit : 2;
        }
    }

    public record Output(
            String completionStatus, boolean completionSucceeded, List<String> responses, int failed, int skipped) {}

    @Override
    public Output handleRequest(Input input) {
        var config = MapConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.shouldComplete(status -> {
                    if (status.successCount() >= input.requiredSuccesses()) {
                        return complete(CUSTOM_COMPLETION_SUCCEEDED);
                    }
                    if (status.failureCount() >= input.failureLimit()) {
                        return complete(CUSTOM_COMPLETION_FAILED);
                    }
                    return continueExecution();
                }))
                .build();

        var result = map(
                "query-providers",
                input.providers(),
                String.class,
                provider -> {
                    var index = MapItemContext.getCurrentContext().getIndex();
                    return step(
                            "query-" + index,
                            String.class,
                            () -> {
                                if (provider.startsWith("bad-")) {
                                    throw new RuntimeException("Provider unavailable: " + provider);
                                }
                                return "response:" + provider;
                            },
                            StepConfig.builder()
                                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                    .build());
                },
                config);

        var skipped = (int)
                result.items().stream().filter(item -> item.status() == SKIPPED).count();
        return new Output(
                result.completionReason().name(),
                result.completionReason().isSucceeded(),
                result.succeeded(),
                result.failed().size(),
                skipped);
    }
}
