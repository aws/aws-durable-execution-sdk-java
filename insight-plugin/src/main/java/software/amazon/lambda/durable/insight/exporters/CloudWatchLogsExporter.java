// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClientBuilder;
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceAlreadyExistsException;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records to a specific CloudWatch Logs group via PutLogEvents, emitting the
 * {@code operationsByName} map. Mirrors the JS {@code CloudWatchLogsExporter}. Requires {@code logs:CreateLogStream}
 * and {@code logs:PutLogEvents} on the target log group.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class CloudWatchLogsExporter implements InsightExporter {
    private final String logGroupName;
    private final String logStreamPrefix;
    private final Integer maxRecordSizeBytes;
    private final CloudWatchLogsClient client;
    private final Set<String> createdStreams = new HashSet<>();

    private CloudWatchLogsExporter(Builder b) {
        this.logGroupName = b.logGroupName;
        this.logStreamPrefix = b.logStreamPrefix != null ? b.logStreamPrefix : "workflow-insight/";
        this.maxRecordSizeBytes = b.maxRecordSizeBytes != null ? b.maxRecordSizeBytes : 256_000;
        CloudWatchLogsClientBuilder cb = CloudWatchLogsClient.builder();
        if (b.region != null) {
            cb = cb.region(Region.of(b.region));
        }
        this.client = b.client != null ? b.client : cb.build();
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
        return record.toByNameWireMap();
    }

    @Override
    public void export(WorkflowInsightRecord record) {
        String streamName = buildStreamName();
        ensureStream(streamName);
        client.putLogEvents(PutLogEventsRequest.builder()
                .logGroupName(logGroupName)
                .logStreamName(streamName)
                .logEvents(List.of(InputLogEvent.builder()
                        .timestamp(System.currentTimeMillis())
                        .message(Json.stringify(render(record)))
                        .build()))
                .build());
    }

    private String buildStreamName() {
        java.time.ZonedDateTime d = Instant.now().atZone(java.time.ZoneOffset.UTC);
        return String.format("%s%d/%02d/%02d", logStreamPrefix, d.getYear(), d.getMonthValue(), d.getDayOfMonth());
    }

    private void ensureStream(String streamName) {
        if (createdStreams.contains(streamName)) {
            return;
        }
        try {
            client.createLogStream(CreateLogStreamRequest.builder()
                    .logGroupName(logGroupName)
                    .logStreamName(streamName)
                    .build());
        } catch (ResourceAlreadyExistsException ignored) {
            // stream already exists — fine
        }
        createdStreams.add(streamName);
    }

    /** Builder for {@link CloudWatchLogsExporter}. */
    public static final class Builder {
        private String logGroupName;
        private String logStreamPrefix;
        private String region;
        private Integer maxRecordSizeBytes;
        private CloudWatchLogsClient client;

        public Builder logGroupName(String logGroupName) {
            this.logGroupName = logGroupName;
            return this;
        }

        public Builder logStreamPrefix(String logStreamPrefix) {
            this.logStreamPrefix = logStreamPrefix;
            return this;
        }

        public Builder region(String region) {
            this.region = region;
            return this;
        }

        public Builder maxRecordSizeBytes(Integer maxRecordSizeBytes) {
            this.maxRecordSizeBytes = maxRecordSizeBytes;
            return this;
        }

        /** Test seam: inject a client. */
        public Builder client(CloudWatchLogsClient client) {
            this.client = client;
            return this;
        }

        public CloudWatchLogsExporter build() {
            return new CloudWatchLogsExporter(this);
        }
    }
}
