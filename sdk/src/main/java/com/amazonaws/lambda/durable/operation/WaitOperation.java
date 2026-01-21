// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.execution.ExecutionPhase;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Phaser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.WaitOptions;

public class WaitOperation implements DurableOperation<Void> {

    private static final Logger logger = LoggerFactory.getLogger(WaitOperation.class);

    private final String operationId;
    private final String name;
    private final Duration duration;
    private final ExecutionManager executionManager;
    private final Phaser phaser;

    public WaitOperation(String operationId, String name, Duration duration, ExecutionManager executionManager) {
        this.operationId = operationId;
        this.name = name;
        this.duration = duration;
        this.executionManager = executionManager;

        this.phaser = executionManager.startPhaser(operationId);
    }

    @Override
    public String getOperationId() {
        return operationId;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void execute() {
        var existing = executionManager.getOperationAndUpdateReplayState(operationId);

        if (existing != null && existing.status() == OperationStatus.SUCCEEDED) {
            // Wait already completed
            phaser.arriveAndDeregister();
            return;
        }

        // Calculate remaining wait time
        // TODO: if the checkpoint is slow remaining wait time might be off. Track
        // endTimestamp instead and move calculation in front of polling start.
        Duration remainingWaitTime;
        if (existing == null) {
            // First execution - checkpoint with full duration
            var update = OperationUpdate.builder()
                    .id(operationId)
                    .name(name)
                    .parentId(null)
                    .type(OperationType.WAIT)
                    .action(OperationAction.START)
                    .waitOptions(WaitOptions.builder()
                            .waitSeconds((int) duration.toSeconds())
                            .build())
                    .build();

            executionManager.sendOperationUpdate(update).join();
            remainingWaitTime = duration;
        } else {
            // Replay - calculate remaining time from scheduledEndTimestamp
            if (existing.waitDetails() != null && existing.waitDetails().scheduledEndTimestamp() != null) {
                remainingWaitTime =
                        Duration.between(Instant.now(), existing.waitDetails().scheduledEndTimestamp());
            } else {
                remainingWaitTime = duration;
            }
        }

        logger.debug("Remaining wait time: {} seconds", remainingWaitTime.getSeconds());
        // Start polling for wait completion
        // Poll starting at scheduledEndTimestamp + 25ms, every 200ms
        // The polling will complete the phaser when the backend reports SUCCEEDED
        Instant firstPoll = Instant.now().plus(remainingWaitTime).plusMillis(25);
        executionManager.pollForOperationUpdates(operationId, firstPoll, Duration.ofMillis(200));
    }

    @Override
    public Void get() {
        if (phaser.getPhase() == ExecutionPhase.RUNNING.getValue()) {
            phaser.register();

            // Get current context from ThreadLocal
            var currentContext = executionManager.getCurrentContext();

            // Deregister current thread - THIS is where suspension can happen!
            // If no other threads are active, this will throw SuspendExecutionException
            executionManager.deregisterActiveThread(currentContext.contextId());

            // Complete the wait phaser immediately (we don't actually wait in Lambda)
            // The backend handles the wait duration
            phaser.arriveAndAwaitAdvance(); // Phase 0 -> 1

            // Reactivate current thread
            executionManager.registerActiveThreadWithContext(currentContext.contextId(), currentContext.threadType());

            // Complete phase 1
            phaser.arriveAndDeregister();
        }

        return null;
    }
}
