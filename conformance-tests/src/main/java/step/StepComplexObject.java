// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 1-4: Returning complex object */
@SuppressWarnings("unchecked")
public class StepComplexObject extends DurableHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, DurableContext context) {
        return context.step("build-response", Map.class, stepCtx -> {
            String name = (String) input.get("name");
            List<String> tags = (List<String>) input.get("tags");
            return Map.of(
                    "user", Map.of("name", name, "tags", tags),
                    "count", tags.size());
        });
    }
}
