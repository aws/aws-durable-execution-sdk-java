// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static software.amazon.lambda.durable.operation.DurableReplaySafeValueOperation.now;
import static software.amazon.lambda.durable.operation.DurableReplaySafeValueOperation.random;
import static software.amazon.lambda.durable.operation.DurableReplaySafeValueOperation.uuid;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.operation.DurableWaitOperation;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ReplaySafeValueIntegrationTest {
    @Test
    void generatedValuesAreCheckpointedAndReusedAfterReplay() {
        var handlerExecutions = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
                    handlerExecutions.incrementAndGet();
                    var values = new ReplaySafeValues(uuid(), now(), random());
                    DurableWaitOperation.wait("force-replay", Duration.ofSeconds(1));
                    return values;
                })
                .withOutputType(ReplaySafeValues.class);

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertTrue(handlerExecutions.get() >= 2);
        var values = result.getResult();
        assertEquals(values.uuid(), result.getOperation("uuid").getStepResult(UUID.class));
        assertEquals(values.now(), result.getOperation("now").getStepResult(Instant.class));
        assertEquals(values.random(), result.getOperation("random").getStepResult(Double.class));
        assertTrue(values.random() >= 0.0);
        assertTrue(values.random() < 1.0);
        assertStep(
                result.getOperation("uuid").getType(),
                result.getOperation("uuid").getSubtype(),
                "UUID");
        assertStep(
                result.getOperation("now").getType(), result.getOperation("now").getSubtype(), "Now");
        assertStep(
                result.getOperation("random").getType(),
                result.getOperation("random").getSubtype(),
                "Random");
    }

    private static void assertStep(OperationType type, String actualSubtype, String expectedSubtype) {
        assertEquals(OperationType.STEP, type);
        assertEquals(expectedSubtype, actualSubtype);
    }

    record ReplaySafeValues(UUID uuid, Instant now, double random) {}
}
