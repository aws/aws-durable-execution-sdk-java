// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class ExtensionStepConfigTest {
    @Test
    void builderDefaultsToNullStateAndSerDes() {
        var config = ExtensionStepConfig.builder().build();

        assertNull(config.initialState());
        assertNull(config.serDes());
    }

    @Test
    void builderRetainsStateAndSerDes() {
        var serDes = new JacksonSerDes();
        var config = ExtensionStepConfig.<Integer>builder()
                .initialState(42)
                .serDes(serDes)
                .build();

        assertEquals(42, config.initialState());
        assertEquals(serDes, config.serDes());
    }
}
