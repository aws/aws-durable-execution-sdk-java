// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.parallel;

import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.ParallelBranchConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.serde.JacksonSerDes;

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
public class DeserializationFailedParallelExample
        extends DurableHandler<DeserializationFailedParallelExample.Input, String> {

    public record Input(List<String> items) {}

    @Override
    public String handleRequest(Input input, DurableContext context) {
        var logger = context.getLogger();
        var items = input.items();
        logger.info("Starting parallel processing of {} items", items.size());

        var config = ParallelConfig.builder().build();

        var parallel = context.parallel("process-items", config);

        try (parallel) {
            var future = parallel.branch(
                    "process",
                    String.class,
                    branchCtx -> {
                        return branchCtx.step("transform", String.class, stepCtx -> {
                            throw new RuntimeException("Intentional failure for transform");
                        });
                    },
                    ParallelBranchConfig.builder().serDes(new FailedSerDes()).build());

            parallel.get();
            try {
                return future.get();
            } catch (Exception e) {
                return e.getMessage();
            }
        }
    }

    private static class FailedSerDes extends JacksonSerDes {

        @Override
        public <T> T deserialize(String json, TypeToken<T> typeToken) {
            T result = super.deserialize(json, typeToken);
            if (result instanceof RuntimeException ex) {
                throw new SerDesException("Deserialization failed", ex);
            }
            return result;
        }
    }
}
