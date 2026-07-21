// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 3-11: Child context large payload (ReplayChildren mode) */
public class ChildLargePayload extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        String largeResult = context.runInChildContext("large-data-processor", String.class, child -> {
            System.out.println(input);
            System.out.flush();

            String seed = child.step("fetch-seed", String.class, stepCtx -> "seed");

            // Build a large result (>256KB) from the small seed value
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 300000; i++) {
                sb.append(seed);
            }
            return sb.toString();
        });

        context.wait(null, Duration.ofSeconds(2));

        return largeResult;
    }
}
