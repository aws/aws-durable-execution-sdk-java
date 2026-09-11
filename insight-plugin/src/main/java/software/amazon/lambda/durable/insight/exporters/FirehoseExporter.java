// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import static java.util.Objects.requireNonNull;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.PutRecordRequest;
import software.amazon.awssdk.services.firehose.model.Record;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records to Amazon Data Firehose, one PutRecord per record as a newline-terminated JSON line
 * so concatenated deliveries stay parseable as NDJSON. Requires {@code firehose:PutRecord}.
 */
@Experimental
public final class FirehoseExporter implements InsightExporter {
    private final String deliveryStreamName;
    private final OperationsFormat operationsFormat;
    private final Integer maxRecordSizeBytes;
    private final LazyClient<FirehoseClient> client;

    private FirehoseExporter(Builder b) {
        this.deliveryStreamName = requireNonNull(b.deliveryStreamName, "deliveryStreamName");
        this.operationsFormat = b.operationsFormat != null ? b.operationsFormat : OperationsFormat.ARRAY;
        this.maxRecordSizeBytes = b.maxRecordSizeBytes != null ? b.maxRecordSizeBytes : 1_000_000;
        this.client = LazyClient.forSdkClient(
                b.client, "firehose", "software.amazon.awssdk.services.firehose.FirehoseClient", b.region);
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
        String data = Json.stringify(render(record)) + "\n";
        client.get()
                .putRecord(PutRecordRequest.builder()
                        .deliveryStreamName(deliveryStreamName)
                        .record(Record.builder()
                                .data(SdkBytes.fromUtf8String(data))
                                .build())
                        .build());
    }

    /** Builder for {@link FirehoseExporter}. */
    public static final class Builder {
        private String deliveryStreamName;
        private String region;
        private OperationsFormat operationsFormat;
        private Integer maxRecordSizeBytes;
        private FirehoseClient client;

        public Builder deliveryStreamName(String deliveryStreamName) {
            this.deliveryStreamName = deliveryStreamName;
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
        public Builder client(FirehoseClient client) {
            this.client = client;
            return this;
        }

        public FirehoseExporter build() {
            return new FirehoseExporter(this);
        }
    }
}
