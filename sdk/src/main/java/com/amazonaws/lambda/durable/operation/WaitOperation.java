// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

import com.amazonaws.lambda.durable.TypeToken;
import com.amazonaws.lambda.durable.execution.ExecutionManager;
import com.amazonaws.lambda.durable.serde.NoopSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.lambda.durable.validation.ParameterValidator;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.WaitOptions;

public class WaitOperation extends BaseDurableOperation<Void> {

    private static final Logger logger = LoggerFactory.getLogger(WaitOperation.class);
    private static final SerDes NOOP_SER_DES = new NoopSerDes();

    private final Duration duration;

    public WaitOperation(
            String operationId, String name, Duration duration, ExecutionManager executionManager, String parentId) {
        super(
                operationId,
                name,
                OperationType.WAIT,
                TypeToken.get(Void.class),
                NOOP_SER_DES,
                executionManager,
                parentId);
        ParameterValidator.validateDuration(duration, "Wait duration");
        this.duration = duration;
    }

    /** Convenience constructor for root-context operations where parentId is null. */
    public WaitOperation(String operationId, String name, Duration duration, ExecutionManager executionManager) {
        this(operationId, name, duration, executionManager, null);
    }

    @Override
    public void execute() {
        var existing = getOperation();
        Duration remainingWaitTime = duration;

        if (existing != null) {
            validateReplay(existing);
            if (existing.status() == OperationStatus.SUCCEEDED) {
                // Wait already completed
                markAlreadyCompleted();
                return;
            }
            // Replay - calculate remaining time from scheduledEndTimestamp
            // TODO: if the checkpoint is slow remaining wait time might be off. Track
            // endTimestamp instead and move calculation in front of polling start.
            if (existing.waitDetails() != null && existing.waitDetails().scheduledEndTimestamp() != null) {
                remainingWaitTime =
                        Duration.between(Instant.now(), existing.waitDetails().scheduledEndTimestamp());
            }
        } else {
            // First execution - checkpoint with full duration
            var update = OperationUpdate.builder()
                    .action(OperationAction.START)
                    .waitOptions(WaitOptions.builder()
                            .waitSeconds((int) duration.toSeconds())
                            .build());

            sendOperationUpdate(update);
        }

        logger.debug("Remaining wait time: {} seconds", remainingWaitTime.getSeconds());
        pollForOperationUpdates(remainingWaitTime);
    }

    @Override
    public Void get() {
        waitForOperationCompletion();

        return null;
    }
}
