// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class InvokeConfigTest {

    @Test
    void testBuilderWithCustomSerDes() {
        SerDes customSerDes = new JacksonSerDes();

        var config = InvokeConfig.builder().serDes(customSerDes).build();

        assertNotNull(config.serDes());
        assertEquals(customSerDes, config.serDes());
    }

    @Test
    void testBuilderDefaultsAreNull() {
        var config = InvokeConfig.builder().build();

        assertNull(config.serDes());
        assertNull(config.payloadSerDes());
        assertNull(config.tenantId());
        assertNull(config.clientContext());
    }

    @Test
    void testBuilderWithTenantId() {
        var config = InvokeConfig.builder().tenantId("tenant-123").build();

        assertEquals("tenant-123", config.tenantId());
    }

    @Test
    void testBuilderWithClientContext() {
        var clientContext = "eyJjdXN0b20iOnsia2V5IjoidmFsdWUifX0=";

        var config = InvokeConfig.builder().clientContext(clientContext).build();

        assertEquals(clientContext, config.clientContext());
    }

    @Test
    void testBuilderWithNullClientContextOmitsIt() {
        var config = InvokeConfig.builder().clientContext(null).build();

        assertNull(config.clientContext());
    }

    @Test
    void testBuilderWithAllOptions() {
        SerDes payloadSerDes = new JacksonSerDes();
        SerDes resultSerDes = new JacksonSerDes();
        var clientContext = "eyJjdXN0b20iOnsia2V5IjoidmFsdWUifX0=";

        var config = InvokeConfig.builder()
                .payloadSerDes(payloadSerDes)
                .serDes(resultSerDes)
                .tenantId("tenant-123")
                .clientContext(clientContext)
                .build();

        assertEquals(payloadSerDes, config.payloadSerDes());
        assertEquals(resultSerDes, config.serDes());
        assertEquals("tenant-123", config.tenantId());
        assertEquals(clientContext, config.clientContext());
    }

    @Test
    void testToBuilderRoundTripPreservesClientContext() {
        SerDes payloadSerDes = new JacksonSerDes();
        SerDes resultSerDes = new JacksonSerDes();
        var clientContext = "eyJjdXN0b20iOnsia2V5IjoidmFsdWUifX0=";

        var original = InvokeConfig.builder()
                .payloadSerDes(payloadSerDes)
                .serDes(resultSerDes)
                .tenantId("tenant-123")
                .clientContext(clientContext)
                .build();

        var copy = original.toBuilder().build();

        assertEquals(original.payloadSerDes(), copy.payloadSerDes());
        assertEquals(original.serDes(), copy.serDes());
        assertEquals(original.tenantId(), copy.tenantId());
        assertEquals(original.clientContext(), copy.clientContext());
    }

    @Test
    void testToBuilderCanOverrideClientContext() {
        var original = InvokeConfig.builder().clientContext("original").build();

        var updated = original.toBuilder().clientContext("updated").build();

        assertEquals("updated", updated.clientContext());
    }
}
