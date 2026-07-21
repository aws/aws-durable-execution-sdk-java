// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package step;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.serde.SerDes;

/** 1-6: Custom serdes (per-step) - uppercases result on serialization */
public class StepCustomSerdes extends DurableHandler<String, String> {

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
        return context.step(
                "uppercase",
                String.class,
                stepCtx -> input,
                StepConfig.builder().serDes(UPPERCASE_SERDES).build());
    }
}
