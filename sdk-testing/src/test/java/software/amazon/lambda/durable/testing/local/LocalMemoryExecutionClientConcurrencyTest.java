// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing.local;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class LocalMemoryExecutionClientConcurrencyTest {
    private static final int INITIAL_STEPS = 300;
    private static final int ADDED_STEPS = 1500;
    private static final int READS = 100;

    enum Probe {
        NAME_LOOKUP,
        CALLBACK_COMPLETION,
        TEST_RESULT,
        FULL_SNAPSHOT
    }

    @ParameterizedTest
    @EnumSource(Probe.class)
    void inspectionAndCallbackLookupTolerateConcurrentCheckpoints(Probe probe) throws Exception {
        var client = new LocalMemoryExecutionClient();
        for (var index = 0; index < INITIAL_STEPS; index++) {
            addStep(client, "initial-" + index);
        }
        addStep(client, "target");
        var callbackIds = createCallbacks(client);
        runConcurrentCheckpoints(client, probe, callbackIds);
        assertEquals(
                INITIAL_STEPS + 1 + READS + ADDED_STEPS,
                client.getAllOperations().size());
        assertEquals(
                "\"target\"", client.getOperationByName("target").stepDetails().result());
        if (probe == Probe.CALLBACK_COMPLETION) {
            for (var index = 0; index < READS; index++) {
                var callback = client.getOperationByName("callback-" + index);
                assertEquals(OperationStatus.SUCCEEDED, callback.status());
                assertEquals("\"callback-result\"", callback.callbackDetails().result());
            }
        }
    }

    private static void runConcurrentCheckpoints(
            LocalMemoryExecutionClient client, Probe probe, List<String> callbackIds) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var writer = workers.submit(() -> {
                awaitStart(ready, start);
                for (var index = 0; index < ADDED_STEPS; index++) {
                    addStep(client, "added-" + index);
                }
            });
            var reader = workers.submit(() -> {
                awaitStart(ready, start);
                for (var index = 0; index < READS; index++) {
                    inspect(client, probe, callbackIds.get(index));
                }
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            reader.get(30, TimeUnit.SECONDS);
            writer.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void inspect(LocalMemoryExecutionClient client, Probe probe, String callbackId) {
        switch (probe) {
            case NAME_LOOKUP ->
                assertEquals("target", client.getOperationByName("target").name());
            case CALLBACK_COMPLETION ->
                client.completeCallback(callbackId, OperationResult.succeeded("\"callback-result\""));
            case TEST_RESULT -> {
                var result = client.toTestResult(
                        DurableExecutionOutput.success("\"done\""), TypeToken.get(String.class), new JacksonSerDes());
                assertEquals("done", result.getResult());
                assertEquals("target", result.getOperation("target").getName());
                assertEquals(
                        result.getOperations().size(),
                        result.getOperations().stream()
                                .map(operation -> operation.getId())
                                .distinct()
                                .count());
            }
            case FULL_SNAPSHOT -> assertTrue(client.getAllOperations().size() >= INITIAL_STEPS + 1 + READS);
        }
    }

    private static List<String> createCallbacks(LocalMemoryExecutionClient client) {
        var ids = new ArrayList<String>();
        for (var index = 0; index < READS; index++) {
            var name = "callback-" + index;
            var update = OperationUpdate.builder()
                    .id(name)
                    .name(name)
                    .type(OperationType.CALLBACK)
                    .action(OperationAction.START)
                    .build();
            client.checkpoint("arn", "token", List.of(update));
            ids.add(client.getCallbackId(name));
        }
        return ids;
    }

    private static void addStep(LocalMemoryExecutionClient client, String name) {
        var update = OperationUpdate.builder()
                .id(name)
                .name(name)
                .type(OperationType.STEP)
                .action(OperationAction.SUCCEED)
                .payload("\"" + name + "\"")
                .build();
        client.checkpoint("arn", "token", List.of(update));
    }

    private static void awaitStart(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            assertTrue(start.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
