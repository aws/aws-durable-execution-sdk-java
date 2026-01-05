// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import com.amazonaws.lambda.durable.model.ExecutionStatus;

class CloudDurableTestRunnerTest {

    @Test
    void testConfiguration() {
        var runner = CloudDurableTestRunner.create(
            "arn:aws:lambda:us-east-2:123:function:test",
            String.class,
            String.class
        )
        .withPollInterval(Duration.ofSeconds(5))
        .withInvocationType(InvocationType.EVENT);
        
        assertNotNull(runner);
    }

    @Test
    void testPlaceholderMethods() {
        var runner = CloudDurableTestRunner.create(
            "arn:aws:lambda:us-east-2:123:function:test",
            String.class,
            String.class
        );
        
        assertThrows(IllegalStateException.class, () -> runner.getOperation("test"));
    }
}
