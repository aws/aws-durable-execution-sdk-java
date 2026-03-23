// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class MapConfigTest {

    @Test
    void defaultBuilder_hasNullMaxConcurrency() {
        var config = MapConfig.builder().build();

        assertEquals(Integer.MAX_VALUE, config.maxConcurrency());
    }

    @Test
    void defaultBuilder_completionConfigDefaultsToAllCompleted() {
        var config = MapConfig.builder().build();

        var completion = config.completionConfig();
        assertNotNull(completion);
        assertNull(completion.minSuccessful());
        assertNull(completion.toleratedFailureCount());
        assertNull(completion.toleratedFailurePercentage());
    }

    @Test
    void defaultBuilder_hasNullSerDes() {
        var config = MapConfig.builder().build();

        assertNull(config.serDes());
    }

    @Test
    void builderWithMaxConcurrency() {
        var config = MapConfig.builder().maxConcurrency(5).build();

        assertEquals(5, config.maxConcurrency());
    }

    @Test
    void builderWithCompletionConfig() {
        var completion = CompletionConfig.allSuccessful();

        var config = MapConfig.builder().completionConfig(completion).build();

        assertSame(completion, config.completionConfig());
    }

    @Test
    void builderWithSerDes() {
        var serDes = new JacksonSerDes();

        var config = MapConfig.builder().serDes(serDes).build();

        assertSame(serDes, config.serDes());
    }

    @Test
    void builderChaining() {
        var completion = CompletionConfig.firstSuccessful();
        var serDes = new JacksonSerDes();

        var config = MapConfig.builder()
                .maxConcurrency(3)
                .completionConfig(completion)
                .serDes(serDes)
                .build();

        assertEquals(3, config.maxConcurrency());
        assertSame(completion, config.completionConfig());
        assertSame(serDes, config.serDes());
    }

    @Test
    void toBuilder_preservesValues() {
        var completion = CompletionConfig.minSuccessful(2);
        var serDes = new JacksonSerDes();
        var original = MapConfig.builder()
                .maxConcurrency(4)
                .completionConfig(completion)
                .serDes(serDes)
                .build();

        var copy = original.toBuilder().build();

        assertEquals(4, copy.maxConcurrency());
        assertSame(completion, copy.completionConfig());
        assertSame(serDes, copy.serDes());
    }

    @Test
    void toBuilder_canOverrideValues() {
        var original = MapConfig.builder().maxConcurrency(4).build();

        var modified = original.toBuilder().maxConcurrency(10).build();

        assertEquals(10, modified.maxConcurrency());
        assertEquals(4, original.maxConcurrency());
    }

    @Test
    void builderWithZeroMaxConcurrency_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> MapConfig.builder().maxConcurrency(0).build());
        assertEquals("maxConcurrency must be at least 1, got: 0", exception.getMessage());
    }

    @Test
    void builderWithNegativeMaxConcurrency_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> MapConfig.builder().maxConcurrency(-1).build());
        assertEquals("maxConcurrency must be at least 1, got: -1", exception.getMessage());
    }

    @Test
    void builderWithNullMaxConcurrency_shouldPass() {
        var config = MapConfig.builder().maxConcurrency(null).build();
        assertEquals(Integer.MAX_VALUE, config.maxConcurrency());
    }
}
