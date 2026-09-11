// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import java.time.Instant;
import java.util.Map;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records to Amazon EventBridge, one PutEvents entry per record with the record status as
 * {@code DetailType} and the record JSON as {@code Detail}. Requires {@code events:PutEvents}.
 */
@Experimental
public final class EventBridgeExporter implements InsightExporter {
    private final String eventBusName;
    private final String source;
    private final OperationsFormat operationsFormat;
    private final Integer maxRecordSizeBytes;
    private final LazyClient<EventBridgeClient> client;

    private EventBridgeExporter(Builder b) {
        this.eventBusName = b.eventBusName != null ? b.eventBusName : "default";
        this.source = b.source != null ? b.source : "aws.durable-execution.insight";
        this.operationsFormat = b.operationsFormat != null ? b.operationsFormat : OperationsFormat.ARRAY;
        this.maxRecordSizeBytes = b.maxRecordSizeBytes != null ? b.maxRecordSizeBytes : 256_000;
        this.client = LazyClient.forSdkClient(
                b.client, "eventbridge", "software.amazon.awssdk.services.eventbridge.EventBridgeClient", b.region);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Integer maxRecordSizeBytes() {
        return maxRecordSizeBytes;
    }

    @Override
    public Object render(WorkflowInsightRecord record) {
        return operationsFormat.apply(record);
    }

    @Override
    public void export(WorkflowInsightRecord record) {
        EventBridgeClient eventBridge = client.get();
        Map<String, Object> detail = operationsFormat.apply(record);
        PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName(eventBusName)
                .source(source)
                .detailType(record.status())
                .detail(Json.stringify(detail))
                .time(Instant.parse((String) detail.get("emittedAt")))
                .build();
        PutEventsResponse response =
                eventBridge.putEvents(PutEventsRequest.builder().entries(entry).build());
        Integer failed = response.failedEntryCount();
        if (failed != null && failed > 0) {
            PutEventsResultEntry result =
                    response.entries().isEmpty() ? null : response.entries().get(0);
            throw new IllegalStateException("EventBridge PutEvents failed: "
                    + (result != null ? result.errorCode() : null) + " — "
                    + (result != null ? result.errorMessage() : null));
        }
    }

    /** Builder for {@link EventBridgeExporter}. */
    public static final class Builder {
        private String eventBusName;
        private String source;
        private String region;
        private OperationsFormat operationsFormat;
        private Integer maxRecordSizeBytes;
        private EventBridgeClient client;

        /** Event bus name or ARN. Default {@code default}. */
        public Builder eventBusName(String eventBusName) {
            this.eventBusName = eventBusName;
            return this;
        }

        /** Event source. Default {@code aws.durable-execution.insight}. */
        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder region(String region) {
            this.region = region;
            return this;
        }

        public Builder operationsFormat(OperationsFormat operationsFormat) {
            this.operationsFormat = operationsFormat;
            return this;
        }

        public Builder maxRecordSizeBytes(Integer maxRecordSizeBytes) {
            this.maxRecordSizeBytes = maxRecordSizeBytes;
            return this;
        }

        /** Test seam: inject a client. */
        public Builder client(EventBridgeClient client) {
            this.client = client;
            return this;
        }

        public EventBridgeExporter build() {
            return new EventBridgeExporter(this);
        }
    }
}
