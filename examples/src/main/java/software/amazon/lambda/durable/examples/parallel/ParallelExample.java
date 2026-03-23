// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.parallel;

import java.util.ArrayList;
import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.model.ParallelResult;

/**
 * Example demonstrating parallel branch execution with the Durable Execution SDK.
 *
 * <p>This handler processes a list of items concurrently using {@code context.parallel()}:
 *
 * <ol>
 *   <li>Each item is processed in its own branch (child context)
 *   <li>All branches run concurrently and their results are collected
 *   <li>A final step combines the results into a summary
 * </ol>
 *
 * <p>The {@link ParallelDurableFuture} implements {@link AutoCloseable}, so try-with-resources guarantees
 * {@code join()} is called even if an exception occurs.
 */
public class ParallelExample extends DurableHandler<ParallelExample.Input, ParallelExample.Output> {

    public record Input(List<String> items) {}

    public record Output(List<String> results, int totalProcessed) {}

    @Override
    public Output handleRequest(Input input, DurableContext context) {
        var logger = context.getLogger();
        var items = input.items();
        logger.info("Starting parallel processing of {} items", items.size());

        var config = ParallelConfig.builder().build();

        var futures = new ArrayList<DurableFuture<String>>(items.size());
        var parallel = context.parallel("process-items", config);

        try (parallel) {
            for (var item : items) {
                var future = parallel.branch("process-" + item, String.class, branchCtx -> {
                    branchCtx.getLogger().info("Processing item: {}", item);
                    return branchCtx.step("transform-" + item, String.class, stepCtx -> item.toUpperCase());
                });
                futures.add(future);
            }
        } // join() called here via AutoCloseable

        ParallelResult parallelResult = parallel.get();
        logger.info(
                "Parallel complete: total={}, succeeded={}, failed={}",
                parallelResult.size(),
                parallelResult.succeeded(),
                parallelResult.failed());

        var results = futures.stream().map(DurableFuture::get).toList();

        return new Output(results, results.size());
    }
}
