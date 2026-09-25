// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.lmi;

import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateResponse;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.client.DurableExecutionClient;

/** Delegates to the real backend without logging checkpoint tokens or payloads. */
final class ObservedClient implements DurableExecutionClient {
    private final DurableExecutionClient delegate;
    private final InvocationTrace trace;

    ObservedClient(DurableExecutionClient delegate, InvocationTrace trace) {
        this.delegate = delegate;
        this.trace = trace;
    }

    @Override
    public CheckpointDurableExecutionResponse checkpoint(String arn, String token, List<OperationUpdate> updates) {
        var callId = trace.nextApiCallId();
        trace.event(
                "CHECKPOINT_CALL",
                Map.of(
                        "callId",
                        callId,
                        "operations",
                        updates.stream()
                                .map(update -> update.id() + ":" + update.type() + ":" + update.action())
                                .toList()));
        try {
            return delegate.checkpoint(arn, token, updates);
        } finally {
            trace.event("CHECKPOINT_EXIT", Map.of("callId", callId));
        }
    }

    @Override
    public GetDurableExecutionStateResponse getExecutionState(String arn, String token, String marker) {
        var callId = trace.nextApiCallId();
        trace.event("POLL_CALL", Map.of("callId", callId));
        try {
            return delegate.getExecutionState(arn, token, marker);
        } finally {
            trace.event("POLL_EXIT", Map.of("callId", callId));
        }
    }
}
