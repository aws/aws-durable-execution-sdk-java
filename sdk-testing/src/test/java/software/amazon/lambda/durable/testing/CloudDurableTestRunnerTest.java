// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.lambda.durable.offload.PayloadOffloader;

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
    void payloadOffloaderConfigurationIsFluent() {
        var mockClient = mock(LambdaClient.class);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var runner = CloudDurableTestRunner.create(
                            "arn:aws:lambda:us-east-2:123:function:test", String.class, String.class, mockClient)
                    .withPayloadOffloader(PayloadOffloader.disabled())
                    .withPayloadOffloadExecutorService(executor);

            assertNotNull(runner);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void testPlaceholderMethods() {
        var mockClient = mock(LambdaClient.class);
        var runner = CloudDurableTestRunner.create(
                "arn:aws:lambda:us-east-2:123:function:test", String.class, String.class, mockClient);

        assertThrows(IllegalStateException.class, () -> runner.getOperation("test"));
    }
}
