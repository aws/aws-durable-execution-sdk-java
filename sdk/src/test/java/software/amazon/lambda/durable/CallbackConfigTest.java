// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class CallbackConfigTest {

    @Test
    void testBuilderWithCustomSerDes() {
        SerDes customSerDes = new JacksonSerDes();

        var config = CallbackConfig.builder().serDes(customSerDes).build();

        assertNotNull(config.serDes());
        assertEquals(customSerDes, config.serDes());
    }

    @Test
    void testBuilderWithoutCustomSerDes() {
        var config = CallbackConfig.builder().build();

        assertNull(config.serDes());
    }

    @Test
    void testBuilderWithNullSerDes() {
        var config = CallbackConfig.builder().serDes(null).build();

        assertNull(config.serDes());
    }

    @Test
    void testBuilderWithAllOptions() {
        var timeout = Duration.ofMinutes(5);
        var heartbeatTimeout = Duration.ofSeconds(30);
        SerDes customSerDes = new JacksonSerDes();

        var config = CallbackConfig.builder()
                .timeout(timeout)
                .heartbeatTimeout(heartbeatTimeout)
                .serDes(customSerDes)
                .build();

        assertEquals(timeout, config.timeout());
        assertEquals(heartbeatTimeout, config.heartbeatTimeout());
        assertEquals(customSerDes, config.serDes());
    }
}
