// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.Event;
import software.amazon.awssdk.services.lambda.model.EventType;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionHistoryRequest;

/**
 * Polls the GetDurableExecutionHistory API until execution completes or a timeout is reached. Used by
 * {@link CloudDurableTestRunner} for synchronous test execution.
 */
public class HistoryPoller {
    private final LambdaClient lambdaClient;

    /** Creates a poller backed by the given Lambda client. */
    public HistoryPoller(LambdaClient lambdaClient) {
        this.lambdaClient = lambdaClient;
    }

    /**
     * Polls execution history until a terminal event is found or the timeout is exceeded.
     *
     * @param executionArn the durable execution ARN to poll
     * @param pollInterval the interval between poll requests
     * @param timeout the maximum time to wait for completion
     * @return all history events collected during polling
     * @throws RuntimeException if the timeout is exceeded or polling is interrupted
     */
    public List<Event> pollUntilComplete(String executionArn, Duration pollInterval, Duration timeout) {
        var allEvents = new ArrayList<Event>();
        var startTime = Instant.now();
        String marker = null;

        while (Duration.between(startTime, Instant.now()).compareTo(timeout) < 0) {
            var request = GetDurableExecutionHistoryRequest.builder()
                    .durableExecutionArn(executionArn)
                    .includeExecutionData(true)
                    .marker(marker)
                    .build();

            var response = lambdaClient.getDurableExecutionHistory(request);
            var events = response.events();

            allEvents.addAll(events);

            if (isExecutionComplete(events)) {
                return allEvents;
            }

            marker = response.nextMarker();
            if (marker == null && events.isEmpty()) {
                // No more events and no new events - wait and try again
            }

            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Polling interrupted", e);
            }
        }

        throw new RuntimeException("Execution timeout exceeded");
    }

    private boolean isExecutionComplete(List<Event> events) {
        return events.stream().anyMatch(event -> {
            var eventType = event.eventType();
            return EventType.EXECUTION_SUCCEEDED.equals(eventType) || EventType.EXECUTION_FAILED.equals(eventType);
        });
    }
}
