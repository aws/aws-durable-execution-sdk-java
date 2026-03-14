// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CompletionConfigTest {

    @Test
    void allSuccessful_zeroFailuresTolerated() {
        var config = CompletionConfig.allSuccessful();

        assertNull(config.minSuccessful());
        assertEquals(0, config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
    }

    @Test
    void allCompleted_allFieldsNull() {
        var config = CompletionConfig.allCompleted();

        assertNull(config.minSuccessful());
        assertNull(config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
    }

    @Test
    void firstSuccessful_minSuccessfulIsOne() {
        var config = CompletionConfig.firstSuccessful();

        assertEquals(1, config.minSuccessful());
        assertNull(config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
    }

    @Test
    void minSuccessful_setsCount() {
        var config = CompletionConfig.minSuccessful(5);

        assertEquals(5, config.minSuccessful());
        assertNull(config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
    }

    @Test
    void toleratedFailureCount_setsCount() {
        var config = CompletionConfig.toleratedFailureCount(3);

        assertNull(config.minSuccessful());
        assertEquals(3, config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
    }

    @Test
    void toleratedFailurePercentage_setsPercentage() {
        var config = CompletionConfig.toleratedFailurePercentage(0.25);

        assertNull(config.minSuccessful());
        assertNull(config.toleratedFailureCount());
        assertEquals(0.25, config.toleratedFailurePercentage());
    }
}
