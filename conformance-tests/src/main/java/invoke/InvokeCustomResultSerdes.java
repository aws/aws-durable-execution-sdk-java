// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.serde.SerDes;

/** 5-16: Invoke with custom result serdes */
public class InvokeCustomResultSerdes extends DurableHandler<Object, String> {

    private static final SerDes UPPERCASE_RESULT_SERDES = new SerDes() {
        @Override
        public String serialize(Object value) {
            return value != null ? "\"" + value.toString() + "\"" : null;
        }

        @Override
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            @SuppressWarnings("unchecked")
            T result = (T) (data != null ? data.toUpperCase() : null);
            return result;
        }
    };

    @Override
    public String handleRequest(Object input, DurableContext context) {
        String functionName = System.getenv("TARGET_FUNCTION_NAME");
        return context.invoke(
                "invoke-custom-result",
                functionName,
                input,
                String.class,
                InvokeConfig.builder().serDes(UPPERCASE_RESULT_SERDES).build());
    }
}
