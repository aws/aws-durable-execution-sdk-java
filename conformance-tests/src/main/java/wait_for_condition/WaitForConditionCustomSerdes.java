// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package wait_for_condition;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.serde.SerDes;

/** 6-11: Wait-for-condition with custom state serdes */
public class WaitForConditionCustomSerdes extends DurableHandler<Object, String> {

    /**
     * Custom SerDes that encodes the string state with a visible "ENC:" prefix and decodes by stripping it, exercising
     * the serialization round-trip across suspensions. The "ENC:" prefix (matching the JS and Python examples) is a
     * clearly non-standard encoding, so the test detects a regression where the SDK ignores the configured serdes and
     * falls back to its default JSON serializer (which would emit a plain JSON-quoted string, not an "ENC:"-prefixed
     * value).
     */
    private static final SerDes CUSTOM_SERDES = new SerDes() {
        @Override
        public String serialize(Object value) {
            if (value == null) {
                return null;
            }
            // Encode: prefix with "ENC:" (non-standard, so custom serdes use is provable)
            return "ENC:" + value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            if (data == null) {
                return null;
            }
            // Decode: strip the "ENC:" prefix
            if (data.startsWith("ENC:")) {
                return (T) data.substring(4);
            }
            return (T) data;
        }
    };

    @Override
    public String handleRequest(Object input, DurableContext context) {
        return context.waitForCondition(
                null,
                String.class,
                (state, stepCtx) -> {
                    String current = state != null ? state : "";
                    String next = current + "x";
                    if (next.length() >= 2) {
                        return WaitForConditionResult.stopPolling(next);
                    }
                    return WaitForConditionResult.continuePolling(next);
                },
                WaitForConditionConfig.<String>builder()
                        .initialState("")
                        .serDes(CUSTOM_SERDES)
                        .build());
    }
}
