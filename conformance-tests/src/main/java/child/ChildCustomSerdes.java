// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.serde.SerDes;

/** 3-14: Child context with custom serdes (succeed) */
public class ChildCustomSerdes extends DurableHandler<String, String> {

    private static final SerDes UPPERCASE_SERDES = new SerDes() {
        @Override
        public String serialize(Object value) {
            return value != null ? ((String) value).toUpperCase() : null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            return (T) data;
        }
    };

    @Override
    public String handleRequest(String input, DurableContext context) {
        return context.runInChildContext(
                "serdes-child",
                String.class,
                child -> child.step("return-input", String.class, stepCtx -> input),
                RunInChildContextConfig.builder().serDes(UPPERCASE_SERDES).build());
    }
}
