// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextErrorHandler;

class ExtensionContextConfigTest {
    @Test
    void builderUsesOrdinaryChildContextDefaults() {
        var config = ExtensionContextConfig.builder().build();

        assertNotNull(config.childContextConfig());
        assertNull(config.errorHandler());
        assertTrue(config.emitUserFunctionEvents());
        assertFalse(config.suppressLateChildCheckpoints());
    }

    @Test
    void builderRetainsExtensionPolicies() {
        var childConfig = RunInChildContextConfig.builder().isVirtual(true).build();
        ExtensionContextErrorHandler handler = failure -> new RuntimeException(failure.contextName());
        var config = ExtensionContextConfig.builder()
                .childContextConfig(childConfig)
                .errorHandler(handler)
                .emitUserFunctionEvents(false)
                .suppressLateChildCheckpoints(true)
                .build();

        assertEquals(childConfig, config.childContextConfig());
        assertEquals(handler, config.errorHandler());
        assertFalse(config.emitUserFunctionEvents());
        assertTrue(config.suppressLateChildCheckpoints());
    }
}
