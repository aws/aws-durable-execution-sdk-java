package com.amazonaws.lambda.durable.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.Event;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionHistoryRequest;

public class HistoryPoller {
    private final LambdaClient lambdaClient;
    
    public HistoryPoller(LambdaClient lambdaClient) {
        this.lambdaClient = lambdaClient;
    }
    
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
            var eventType = event.eventTypeAsString();
            return "ExecutionSucceeded".equals(eventType) || "ExecutionFailed".equals(eventType);
        });
    }
}
