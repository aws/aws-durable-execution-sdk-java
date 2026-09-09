// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.OperationSummary;
import software.amazon.lambda.durable.insight.OperationsIndex;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records to Amazon SQS via {@code SendMessage}, one message per emission with the full JSON
 * as the message body. Mirrors the JS {@code SQSExporter}.
 *
 * <p>FIFO queues are detected by a {@code .fifo} suffix on the queue URL. For a FIFO queue the message group id
 * defaults to the record's {@code executionArn} (so all messages for one execution are ordered together) unless an
 * explicit {@code messageGroupId} is configured, and the message deduplication id is derived from
 * {@code executionArn:emittedAt} so a redelivery of the same emission is de-duplicated while a later update to the same
 * execution is not. Standard queues carry neither field. Every message additionally carries {@code status} and
 * {@code functionName} string message attributes for consumer-side filtering.
 *
 * <p>Use this exporter when you need guaranteed delivery to a single consumer, or want to decouple record processing
 * from the Lambda invocation. Requires {@code sqs:SendMessage} on the target queue.
 */
@Experimental
public final class SQSExporter implements InsightExporter {

    /** How operations are rendered in the message body. Mirrors the JS {@code OperationsFormat}. */
    @Experimental
    public enum OperationsFormat {
        /** The canonical {@code operations} array (default). */
        ARRAY,
        /** The {@code operationsByName} map, with the {@code operations} array replaced. */
        BY_NAME,
        /** The canonical {@code operations} array plus an added {@code operationsByName} map. */
        BOTH
    }

    private final String queueUrl;
    private final String messageGroupId;
    private final boolean isFifo;
    private final OperationsFormat operationsFormat;
    private final Integer maxRecordSizeBytes;
    private final SqsClient client;

    private SQSExporter(Builder b) {
        this.queueUrl = b.queueUrl;
        this.messageGroupId = b.messageGroupId;
        this.isFifo = b.queueUrl.endsWith(".fifo");
        this.operationsFormat = b.operationsFormat != null ? b.operationsFormat : OperationsFormat.ARRAY;
        this.maxRecordSizeBytes = b.maxRecordSizeBytes != null ? b.maxRecordSizeBytes : 256_000;
        SqsClientBuilder cb = SqsClient.builder();
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
        switch (operationsFormat) {
            case BY_NAME:
                return record.toByNameWireMap();
            case BOTH:
                return toBothWireMap(record);
            case ARRAY:
            default:
                return record.toWireMap();
        }
    }

    /**
     * The {@code "both"} shape: the canonical {@code operations} array wire map with an added {@code operationsByName}
     * map. Built from the record's public wire map and operations so the plugin's {@code WorkflowInsightRecord} does
     * not need a dedicated rendering.
     */
    private static Map<String, Object> toBothWireMap(WorkflowInsightRecord record) {
        Map<String, Object> data = record.toWireMap();
        Map<String, Object> byName = new LinkedHashMap<>();
        for (Map.Entry<String, OperationSummary> e :
                OperationsIndex.buildOperationsByName(record.operations()).entrySet()) {
            byName.put(e.getKey(), e.getValue().toWireMap());
        }
        data.put("operationsByName", byName);
        return data;
    }

    @Override
    public void export(WorkflowInsightRecord record) {
        Object rendered = render(record);
        String body = Json.stringify(rendered);
        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        if (record.status() != null) {
            attributes.put("status", stringAttribute(record.status()));
        }
        if (record.functionName() != null) {
            attributes.put("functionName", stringAttribute(record.functionName()));
        }

        SendMessageRequest.Builder request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .messageAttributes(attributes);
        if (isFifo) {
            request = request.messageGroupId(messageGroupId != null ? messageGroupId : record.executionArn())
                    .messageDeduplicationId(record.executionArn() + ":" + emittedAt(rendered));
        }
        client.sendMessage(request.build());
    }

    /**
     * Reads {@code emittedAt} from the rendered wire map. Every render shape ({@code ARRAY}/{@code BY_NAME}/
     * {@code BOTH}) carries the base scalar fields, so this avoids depending on a package-private record accessor. The
     * value is only used to build a FIFO deduplication id.
     */
    private static String emittedAt(Object rendered) {
        if (rendered instanceof Map<?, ?> map) {
            Object value = map.get("emittedAt");
            if (value != null) {
                return value.toString();
            }
        }
        return "";
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

        /** The target SQS queue URL. A URL ending in {@code .fifo} is treated as a FIFO queue. Required. */
        public Builder queueUrl(String queueUrl) {
            this.queueUrl = queueUrl;
            return this;
        }

        /**
         * The FIFO message group id. Ignored for standard queues. Defaults to the record's {@code executionArn} for a
         * FIFO queue when unset.
         */
        public Builder messageGroupId(String messageGroupId) {
            this.messageGroupId = messageGroupId;
            return this;
        }

        /** AWS region for the created client. Ignored when a client is injected. Defaults to the SDK default chain. */
        public Builder region(String region) {
            this.region = region;
            return this;
        }

        /** How operations are rendered in the message body. Defaults to {@link OperationsFormat#ARRAY}. */
        public Builder operationsFormat(OperationsFormat operationsFormat) {
            this.operationsFormat = operationsFormat;
            return this;
        }

        /** Maximum serialized record size before truncation. Defaults to SQS's 256 KB message limit. */
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
            if (queueUrl == null || queueUrl.isEmpty()) {
                throw new IllegalArgumentException("SQSExporter requires a non-empty queueUrl");
            }
            return new SQSExporter(this);
        }
    }
}
