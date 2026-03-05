// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.WaitOptions;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.serde.NoopSerDes;
import software.amazon.lambda.durable.serde.SerDes;

public class WaitOperation extends BaseDurableOperation<Void> {

    private static final Logger logger = LoggerFactory.getLogger(WaitOperation.class);
    private static final SerDes NOOP_SER_DES = new NoopSerDes();

    private final Duration duration;

    public WaitOperation(String operationId, String name, Duration duration, DurableContext durableContext) {
        super(operationId, name, OperationType.WAIT, TypeToken.get(Void.class), NOOP_SER_DES, durableContext);
        this.duration = duration;
    }

    /** Starts the operation. */
    @Override
    protected void start() {
        Duration remainingWaitTime = duration;

        // First execution - checkpoint with full duration
        var update = OperationUpdate.builder()
                .action(OperationAction.START)
                .waitOptions(WaitOptions.builder()
                        .waitSeconds((int) duration.toSeconds())
                        .build());

        sendOperationUpdate(update);
        logger.debug("Remaining wait time: {} seconds", remainingWaitTime.getSeconds());
        pollForOperationUpdates(remainingWaitTime);
    }

    /** Replays the operation. */
    @Override
    protected void replay(Operation existing) {
        Duration remainingWaitTime = duration;

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
        logger.debug("Remaining wait time: {} seconds", remainingWaitTime.getSeconds());
        pollForOperationUpdates(remainingWaitTime);
    }

    @Override
    public Void get() {
        waitForOperationCompletion();

        return null;
    }
}
