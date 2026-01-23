// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;

class CloudDurableTestRunnerTest {

    @Test
    void testConfiguration() {
        var mockClient = mock(LambdaClient.class);
        var runner = CloudDurableTestRunner.create(
                        "arn:aws:lambda:us-east-2:123:function:test", String.class, String.class, mockClient)
                .withPollInterval(Duration.ofSeconds(5))
                .withInvocationType(InvocationType.EVENT);

        assertNotNull(runner);
    }

    @Test
    void testPlaceholderMethods() {
        var mockClient = mock(LambdaClient.class);
        var runner = CloudDurableTestRunner.create(
                "arn:aws:lambda:us-east-2:123:function:test", String.class, String.class, mockClient);

        assertThrows(IllegalStateException.class, () -> runner.getOperation("test"));
    }
}
