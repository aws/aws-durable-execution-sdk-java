// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records to Amazon SQS, one SendMessage per record with the record JSON as the body and
 * {@code status} / {@code functionName} message attributes. On a FIFO queue the group id defaults to the execution ARN
 * and the deduplication id is the execution ARN plus {@code emittedAt}. Requires {@code sqs:SendMessage}.
 */
@Experimental
public final class SQSExporter implements InsightExporter {
    private final String queueUrl;
    private final String messageGroupId;
    private final boolean fifo;
    private final OperationsFormat operationsFormat;
    private final Integer maxRecordSizeBytes;
    private final LazyClient<SqsClient> client;

    private SQSExporter(Builder b) {
        this.queueUrl = requireNonNull(b.queueUrl, "queueUrl");
        this.messageGroupId = b.messageGroupId;
        this.fifo = queueUrl.endsWith(".fifo");
        this.operationsFormat = b.operationsFormat != null ? b.operationsFormat : OperationsFormat.ARRAY;
        this.maxRecordSizeBytes = b.maxRecordSizeBytes != null ? b.maxRecordSizeBytes : 256_000;
        this.client =
                LazyClient.forSdkClient(b.client, "sqs", "software.amazon.awssdk.services.sqs.SqsClient", b.region);
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
        SqsClient sqs = client.get();
        Map<String, Object> body = operationsFormat.apply(record);
        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("status", stringAttribute(record.status()));
        attributes.put("functionName", stringAttribute(record.functionName()));
        SendMessageRequest.Builder rb = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(Json.stringify(body))
                .messageAttributes(attributes);
        if (fifo) {
            rb.messageGroupId(fifoId(messageGroupId != null ? messageGroupId : record.executionArn()))
                    .messageDeduplicationId(fifoId(record.executionArn() + ":" + body.get("emittedAt")));
        }
        sqs.sendMessage(rb.build());
    }

    /** SQS caps FIFO group and deduplication ids at 128 characters; longer values are replaced by a SHA-256 digest. */
    static String fifoId(String value) {
        if (value.length() <= 128) {
            return value;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(value)
                .build();
    }

    /** Builder for {@link SQSExporter}. */
    public static final class Builder {
        private String queueUrl;
        private String messageGroupId;
        private String region;
        private OperationsFormat operationsFormat;
        private Integer maxRecordSizeBytes;
        private SqsClient client;

        public Builder queueUrl(String queueUrl) {
            this.queueUrl = queueUrl;
            return this;
        }

        /** FIFO message group id. Default: the execution ARN. Ignored for standard queues. */
        public Builder messageGroupId(String messageGroupId) {
            this.messageGroupId = messageGroupId;
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
        public Builder client(SqsClient client) {
            this.client = client;
            return this;
        }

        public SQSExporter build() {
            return new SQSExporter(this);
        }
    }
}
