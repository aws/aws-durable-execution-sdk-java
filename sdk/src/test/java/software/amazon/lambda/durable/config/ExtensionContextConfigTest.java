// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextErrorHandler;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class ExtensionContextConfigTest {
    @Test
    void builderUsesOrdinaryChildContextDefaults() {
        var config = ExtensionContextConfig.builder().build();

        assertNull(config.serDes());
        assertFalse(config.isVirtual());
        assertNull(config.errorHandler());
        assertTrue(config.emitUserFunctionEvents());
        assertFalse(config.suppressLateChildCheckpoints());
        assertFalse(config.validateCompletedReplay());
    }

    @Test
    void builderRetainsExtensionPolicies() {
        var serDes = new JacksonSerDes();
        ExtensionContextErrorHandler handler = failure -> new RuntimeException(failure.contextName());
        var config = ExtensionContextConfig.builder()
                .serDes(serDes)
                .isVirtual(true)
                .errorHandler(handler)
                .emitUserFunctionEvents(false)
                .suppressLateChildCheckpoints(true)
                .validateCompletedReplay(true)
                .build();

        assertSame(serDes, config.serDes());
        assertTrue(config.isVirtual());
        assertEquals(handler, config.errorHandler());
        assertFalse(config.emitUserFunctionEvents());
        assertTrue(config.suppressLateChildCheckpoints());
        assertTrue(config.validateCompletedReplay());
    }
}
