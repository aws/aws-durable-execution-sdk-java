// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records to Amazon S3, one JSON object per execution (keyed by execution name so updates
 * overwrite the same object). Emits the canonical {@code operations}-array wire shape. Mirrors the JS
 * {@code S3Exporter}.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class S3Exporter implements InsightExporter {

    /** How to partition objects in S3. */
    public enum Partitioning {
        DATE,
        FUNCTION_NAME,
        NONE
    }

    private final String bucket;
    private final String prefix;
    private final Partitioning partitioning;
    private final Integer maxRecordSizeBytes;
    private final S3Client client;

    private S3Exporter(Builder b) {
        this.bucket = b.bucket;
        this.prefix = b.prefix != null ? b.prefix : "workflow-insight/";
        this.partitioning = b.partitioning != null ? b.partitioning : Partitioning.DATE;
        this.maxRecordSizeBytes = b.maxRecordSizeBytes != null ? b.maxRecordSizeBytes : 5_000_000;
        S3ClientBuilder cb = S3Client.builder();
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
    public void export(WorkflowInsightRecord record) {
        String key = buildKey(record);
        String body = Json.stringify(record.toWireMap());
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("application/json")
                        .build(),
                RequestBody.fromString(body));
    }

    private String buildKey(WorkflowInsightRecord record) {
        String fileName =
                sanitize(record.executionName() != null ? record.executionName() : record.executionArn()) + ".json";
        return prefix + buildPartition(record) + fileName;
    }

    private String buildPartition(WorkflowInsightRecord record) {
        switch (partitioning) {
            case DATE:
                java.time.ZonedDateTime d =
                        java.time.Instant.parse(record.startTime()).atZone(java.time.ZoneOffset.UTC);
                return String.format("year=%d/month=%02d/day=%02d/", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
            case FUNCTION_NAME:
                return "function=" + sanitize(record.functionName()) + "/";
            case NONE:
            default:
                return "";
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** Builder for {@link S3Exporter}. */
    public static final class Builder {
        private String bucket;
        private String prefix;
        private Partitioning partitioning;
        private String region;
        private Integer maxRecordSizeBytes;
        private S3Client client;

        public Builder bucket(String bucket) {
            this.bucket = bucket;
            return this;
        }

        public Builder prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        public Builder partitioning(Partitioning partitioning) {
            this.partitioning = partitioning;
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
        public Builder client(S3Client client) {
            this.client = client;
            return this;
        }

        public S3Exporter build() {
            return new S3Exporter(this);
        }
    }
}
