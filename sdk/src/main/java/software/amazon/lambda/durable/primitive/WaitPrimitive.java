// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.primitive;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.WaitOptions;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.internal.PrimitiveOperationIdentifier;

/**
 * Durable operation that suspends execution for a specified duration without consuming compute.
 *
 * <p>The wait is checkpointed and the Lambda is suspended. On re-invocation after the wait period, execution resumes
 * from where it left off.
 */
public class WaitPrimitive extends BasePrimitive implements DurableFuture<Void> {

    private static final Logger logger = LoggerFactory.getLogger(WaitPrimitive.class);

    private final Duration duration;

    public WaitPrimitive(
            PrimitiveOperationIdentifier operationIdentifier, Duration duration, DurableContextImpl durableContext) {
        super(operationIdentifier, durableContext, null);
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
        var scheduledEndTimestamp = Instant.now().plusMillis(duration.toMillis());
        var existing = getOperation();
        if (existing != null
                && existing.waitDetails() != null
                && existing.waitDetails().scheduledEndTimestamp() != null) {
            scheduledEndTimestamp = existing.waitDetails().scheduledEndTimestamp();
        }
        pollForOperationUpdates(scheduledEndTimestamp);
    }

    @Override
    public Void get() {
        waitForOperationCompletion();

        return null;
    }
}
