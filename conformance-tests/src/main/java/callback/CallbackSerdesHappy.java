// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package callback;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.serde.SerDes;

/** 4-15: Custom serdes (Date roundtrip - happy path). */
public class CallbackSerdesHappy extends DurableHandler<String, Map<String, Object>> {

    /** A simple POJO for the test data. */
    public static class CustomData {
        public String id;
        public String message;
        public Instant timestamp;
    }

    private static final SerDes CUSTOM_SERDES = new SerDes() {
        @Override
        @SuppressWarnings("unchecked")
        public String serialize(Object value) {
            if (value == null) {
                return null;
            }
            CustomData d = (CustomData) value;
            return String.format(
                    "{\"id\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}", d.id, d.message, d.timestamp.toString());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            if (data == null) {
                return null;
            }
            CustomData out = new CustomData();
            String s = data;
            out.id = extractString(s, "id");
            out.message = extractString(s, "message");
            out.timestamp = Instant.parse(extractString(s, "timestamp"));
            return (T) out;
        }

        private static String extractString(String s, String field) {
            // Match "field": "value" or "field":"value" (with or without space)
            String key = "\"" + field + "\":";
            int i = s.indexOf(key);
            if (i < 0) {
                return "";
            }
            int start = i + key.length();
            // Skip optional whitespace
            while (start < s.length() && s.charAt(start) == ' ') {
                start++;
            }
            // Skip opening quote
            if (start < s.length() && s.charAt(start) == '"') {
                start++;
            }
            int j = s.indexOf("\"", start);
            return j < 0 ? s.substring(start) : s.substring(start, j);
        }
    };

    @Override
    public Map<String, Object> handleRequest(String input, DurableContext context) {
        var config = CallbackConfig.builder().serDes(CUSTOM_SERDES).build();
        var callback = context.createCallback(input, CustomData.class, config);
        CustomData result = callback.get();

        Map<String, Object> received = new HashMap<>();
        received.put("id", result.id);
        received.put("message", result.message);
        received.put("timestamp", result.timestamp.getEpochSecond());

        Map<String, Object> response = new HashMap<>();
        response.put("received", received);
        return response;
    }
}
