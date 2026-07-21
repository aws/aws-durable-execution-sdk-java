// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package callback;

import java.util.HashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.serde.SerDes;

/** 4-16: Custom serdes (numeric type). */
public class CallbackSerdesNumeric extends DurableHandler<String, Map<String, Object>> {

    private static final SerDes NUMERIC_SERDES = new SerDes() {
        @Override
        public String serialize(Object value) {
            return value == null ? null : String.valueOf((Integer) value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            return data == null ? null : (T) Integer.valueOf(data.trim());
        }
    };

    @Override
    public Map<String, Object> handleRequest(String input, DurableContext context) {
        var config = CallbackConfig.builder().serDes(NUMERIC_SERDES).build();
        var callback = context.createCallback(input, Integer.class, config);
        int value = callback.get();

        Map<String, Object> response = new HashMap<>();
        response.put("count", value);
        response.put("doubled", value * 2);
        return response;
    }
}
