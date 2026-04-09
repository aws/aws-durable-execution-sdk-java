// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing.local;

import java.time.Instant;
import java.util.UUID;
import software.amazon.awssdk.services.lambda.model.CallbackDetails;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.awssdk.services.lambda.model.WaitDetails;

public class OperationProcessor {
    /** Applies the update to the existing operation, returning a new operation. */
    static Operation applyUpdate(OperationUpdate update, Operation existingOp) {
        var builder = Operation.builder()
                .id(update.id())
                .name(update.name())
                .type(update.type())
                .subType(update.subType())
                .parentId(update.parentId())
                .status(deriveStatus(update.action()));

        switch (update.type()) {
            case WAIT -> builder.waitDetails(buildWaitDetails(update));
            case STEP -> builder.stepDetails(buildStepDetails(update, existingOp));
            case CALLBACK -> builder.callbackDetails(buildCallbackDetails(update, existingOp));
            case EXECUTION -> {} // No details needed for EXECUTION operations
            case CHAINED_INVOKE -> builder.chainedInvokeDetails(buildChainedInvokeDetails(update));
            case CONTEXT -> builder.contextDetails(buildContextDetails(update));
            case UNKNOWN_TO_SDK_VERSION ->
                throw new UnsupportedOperationException("UNKNOWN_TO_SDK_VERSION not supported");
        }

        return builder.build();
    }

    /** Applies the result of an operation to the existing operation, returning a new operation. */
    public static Operation applyResult(Operation op, OperationResult result) {
        var builder = Operation.builder()
                .id(op.id())
                .name(op.name())
                .type(op.type())
                .subType(op.subType())
                .parentId(op.parentId())
                .status(result.operationStatus());

        switch (op.type()) {
            case WAIT -> builder.waitDetails(buildWaitDetails(result, op));
            case STEP -> builder.stepDetails(buildStepDetails(result, op));
            case CALLBACK -> builder.callbackDetails(buildCallbackDetails(result, op));
            case EXECUTION -> {} // No details needed for EXECUTION operations
            case CHAINED_INVOKE -> builder.chainedInvokeDetails(buildChainedInvokeDetails(result, op));
            case CONTEXT -> builder.contextDetails(buildContextDetails(result, op));
            case UNKNOWN_TO_SDK_VERSION ->
                throw new UnsupportedOperationException("UNKNOWN_TO_SDK_VERSION not supported");
        }
        return builder.build();
    }

    private static ContextDetails buildContextDetails(OperationResult result, Operation op) {
        throw new IllegalArgumentException("Context operation type is not supported");
    }

    private static ChainedInvokeDetails buildChainedInvokeDetails(OperationResult result, Operation op) {
        if (result.operationStatus() == OperationStatus.STOPPED
                || result.operationStatus() == OperationStatus.TIMED_OUT) {
            return op.chainedInvokeDetails().toBuilder().error(result.error()).build();
        }
        throw new IllegalArgumentException("Operation status not supported: " + result.operationStatus());
    }

    private static ChainedInvokeDetails buildChainedInvokeDetails(OperationUpdate update) {
        if (update.action() == OperationAction.START) {
            return ChainedInvokeDetails.builder().build();
        } else {
            return ChainedInvokeDetails.builder()
                    .result(update.payload())
                    .error(update.error())
                    .build();
        }
    }

    private static ContextDetails buildContextDetails(OperationUpdate update) {
        var detailsBuilder = ContextDetails.builder().result(update.payload()).error(update.error());

        if (update.contextOptions() != null
                && Boolean.TRUE.equals(update.contextOptions().replayChildren())) {
            detailsBuilder.replayChildren(true);
        }

        return detailsBuilder.build();
    }

    private static WaitDetails buildWaitDetails(OperationResult result, Operation op) {
        if (result.operationStatus() != OperationStatus.SUCCEEDED) {
            throw new IllegalArgumentException("Operation status is not SUCCEEDED");
        }
        return op.waitDetails().toBuilder().build();
    }

    private static WaitDetails buildWaitDetails(OperationUpdate update) {
        if (update.waitOptions() == null) return null;

        var scheduledEnd = Instant.now().plusSeconds(update.waitOptions().waitSeconds());
        return WaitDetails.builder().scheduledEndTimestamp(scheduledEnd).build();
    }

    private static StepDetails buildStepDetails(OperationUpdate update, Operation existingOp) {
        var existing = existingOp != null ? existingOp.stepDetails() : null;

        var detailsBuilder = existing != null ? existing.toBuilder() : StepDetails.builder();
        var attempt = existing != null && existing.attempt() != null ? existing.attempt() + 1 : 1;

        if (update.action() == OperationAction.FAIL) {
            detailsBuilder.attempt(attempt).error(update.error());
        }

        if (update.action() == OperationAction.RETRY) {
            detailsBuilder
                    .attempt(attempt)
                    .error(update.error())
                    .nextAttemptTimestamp(
                            Instant.now().plusSeconds(update.stepOptions().nextAttemptDelaySeconds()));
        }

        if (update.payload() != null) {
            detailsBuilder.result(update.payload());
        }

        return detailsBuilder.build();
    }

    private static StepDetails buildStepDetails(OperationResult result, Operation op) {
        if (result.operationStatus() == OperationStatus.READY) {
            return op.stepDetails().toBuilder().build();
        }
        throw new IllegalArgumentException("Operation status is not READY");
    }

    private static CallbackDetails buildCallbackDetails(OperationResult result, Operation op) {
        if (result.operationStatus() == OperationStatus.TIMED_OUT) {
            return op.callbackDetails().toBuilder().error(result.error()).build();
        }
        return null;
    }

    private static CallbackDetails buildCallbackDetails(OperationUpdate update, Operation existingOp) {
        var existing = existingOp != null ? existingOp.callbackDetails() : null;

        // Preserve existing callbackId, or generate new one on START
        var callbackId =
                existing != null ? existing.callbackId() : UUID.randomUUID().toString();

        return CallbackDetails.builder()
                .callbackId(callbackId)
                .result(existing != null ? update.payload() : null)
                .error(existing != null ? update.error() : null)
                .build();
    }

    private static OperationStatus deriveStatus(OperationAction action) {
        return switch (action) {
            case START -> OperationStatus.STARTED;
            case SUCCEED -> OperationStatus.SUCCEEDED;
            case FAIL -> OperationStatus.FAILED;
            case RETRY -> OperationStatus.PENDING;
            case CANCEL -> OperationStatus.CANCELLED;
            case UNKNOWN_TO_SDK_VERSION -> OperationStatus.UNKNOWN_TO_SDK_VERSION; // Todo: Check this
        };
    }
}
