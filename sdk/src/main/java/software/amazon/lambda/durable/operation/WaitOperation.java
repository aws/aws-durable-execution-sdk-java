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
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.WaitOptions;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.serde.NoopSerDes;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Durable operation that suspends execution for a specified duration without consuming compute.
 *
 * <p>The wait is checkpointed and the Lambda is suspended. On re-invocation after the wait period, execution resumes
 * from where it left off.
 */
public class WaitOperation extends BaseDurableOperation<Void> {

    private static final Logger logger = LoggerFactory.getLogger(WaitOperation.class);
    private static final SerDes NOOP_SER_DES = new NoopSerDes();

    private final Duration duration;

    public WaitOperation(
            OperationIdentifier operationIdentifier, Duration duration, DurableContextImpl durableContext) {
        super(operationIdentifier, TypeToken.get(Void.class), NOOP_SER_DES, durableContext);
        this.duration = duration;
    }

    /** Starts the operation. */
    @Override
    protected void start() {
        // First execution - checkpoint with full duration
        var update = OperationUpdate.builder()
                .action(OperationAction.START)
                .waitOptions(WaitOptions.builder()
                        .waitSeconds((int) duration.toSeconds())
                        .build());

        sendOperationUpdate(update);
        pollForWaitExpiration();
    }

    /** Replays the operation. */
    @Override
    protected void replay(Operation existing) {
        if (existing.status() == OperationStatus.SUCCEEDED) {
            // Wait already completed
            markAlreadyCompleted();
            return;
        }

        pollForWaitExpiration();
    }

    private void pollForWaitExpiration() {
        // Always calculate remaining time from scheduledEndTimestamp if scheduledEndTimestamp exists
        var remainingWaitTime = duration;
        var existing = getOperation();
        if (existing != null
                && existing.waitDetails() != null
                && existing.waitDetails().scheduledEndTimestamp() != null) {
            remainingWaitTime =
                    Duration.between(Instant.now(), existing.waitDetails().scheduledEndTimestamp());
            // If the wait has already elapsed, poll immediately with a minimal positive interval
            if (remainingWaitTime.isNegative() || remainingWaitTime.isZero()) {
                remainingWaitTime = Duration.ofMillis(1);
            }
        }
        logger.debug("Remaining wait time: {} ms", remainingWaitTime.toMillis());
        pollForOperationUpdates(remainingWaitTime);
    }

    @Override
    public Void get() {
        waitForOperationCompletion();

        return null;
    }
}
