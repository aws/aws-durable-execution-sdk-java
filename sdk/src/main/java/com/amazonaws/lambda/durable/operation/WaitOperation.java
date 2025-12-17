package com.amazonaws.lambda.durable.operation;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Phaser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.lambda.durable.execution.ExecutionCoordinator;
import com.amazonaws.lambda.durable.execution.ThreadType;

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
    private final Phaser phaser;
    private final ExecutionCoordinator coordinator;

    public WaitOperation(
            String operationId,
            String name,
            Duration duration,
            Phaser phaser,
            ExecutionCoordinator coordinator) {
        this.operationId = operationId;
        this.name = name;
        this.duration = duration;
        this.phaser = phaser;
        this.coordinator = coordinator;
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
    public Phaser getPhaser() {
        return phaser;
    }

    @Override
    public void execute() {
        // Check replay
        var existing = coordinator.getOperation(operationId);

        if (existing != null && existing.status() == OperationStatus.SUCCEEDED) {
            // Wait already completed
            phaser.arriveAndDeregister();
            return;
        }

        // Calculate remaining wait time
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

            coordinator.sendOperationUpdate(update).join();
            remainingWaitTime = duration;
        } else {
            // Replay - calculate remaining time from scheduledEndTimestamp
            if (existing.waitDetails() != null && existing.waitDetails().scheduledEndTimestamp() != null) {
                remainingWaitTime = Duration.between(
                        Instant.now(),
                        existing.waitDetails().scheduledEndTimestamp());
            } else {
                remainingWaitTime = duration;
            }
        }

        logger.debug("Remaining wait time: {} seconds", remainingWaitTime.getSeconds());
        // Start polling for wait completion
        // Poll starting at scheduledEndTimestamp + 25ms, every 200ms
        // The polling will complete the phaser when the backend reports SUCCEEDED
        Instant firstPoll = Instant.now()
                .plus(remainingWaitTime)
                .plusMillis(25);
        coordinator.pollForUpdates(operationId, phaser, firstPoll, Duration.ofMillis(200));
    }

    @Override
    public Void get() {
        if (phaser.getPhase() == 0) {
            phaser.register();

            // Deregister current thread - THIS is where suspension can happen!
            // If no other threads are active, this will throw SuspendExecutionException
            coordinator.deregisterActiveThread("Root");

            // Complete the wait phaser immediately (we don't actually wait in Lambda)
            // The backend handles the wait duration
            phaser.arriveAndAwaitAdvance(); // Phase 0 -> 1

            // Reactivate current thread
            coordinator.registerActiveThread("Root", ThreadType.CONTEXT);

            // Complete phase 1
            phaser.arriveAndDeregister();
        }

        return null;
    }
}
