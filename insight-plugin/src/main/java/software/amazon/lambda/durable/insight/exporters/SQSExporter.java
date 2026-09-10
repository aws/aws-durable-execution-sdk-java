// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * explicit {@code messageGroupId} is configured. The message deduplication id is derived from the execution identity,
 * the emitted timestamp, and the rendered body, so two distinct rapid {@code ON_CHANGE} snapshots of the same execution
 * stay distinct (they are not silently dropped inside SQS's 5-minute dedup window) while an exact redelivery of the
 * same emission is de-duplicated. Standard queues carry neither field. Every message additionally carries
 * {@code status} and {@code functionName} string message attributes for consumer-side filtering.
 *
 * <p>SQS caps both the message group id and the message deduplication id at 128 characters. The group id is used
 * verbatim when it already fits and is otherwise replaced by a deterministic SHA-256 hex digest (64 characters) of the
 * raw value, so grouping stays stable per execution without a new dependency. The deduplication id is always a SHA-256
 * hex digest, so it is bounded by construction.
 *
 * <p>{@code maxRecordSizeBytes} bounds the rendered JSON <em>body</em> only. SQS counts message attributes toward the
 * same 256 KB message quota, so the default (256 KB) is intentionally not raised to the exact quota; leave headroom for
 * the {@code status} and {@code functionName} attributes when tuning it.
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
            String groupId = messageGroupId != null ? messageGroupId : record.executionArn();
            request = request.messageGroupId(boundedId(groupId))
                    .messageDeduplicationId(deduplicationId(record.executionArn(), emittedAt(rendered), body));
        }
        client.sendMessage(request.build());
    }

    /** SQS caps the message group id and the message deduplication id at this many characters. */
    private static final int SQS_ID_MAX_LENGTH = 128;

    /**
     * Returns {@code raw} unchanged when it already fits SQS's 128-character id limit; otherwise a deterministic
     * SHA-256 hex digest (64 characters) of it, so a long execution identity still yields a stable, bounded, and
     * collision-resistant group id. {@code null} is passed through so a missing identity fails the same way it would
     * without bounding rather than throwing here.
     */
    private static String boundedId(String raw) {
        if (raw == null || raw.length() <= SQS_ID_MAX_LENGTH) {
            return raw;
        }
        return sha256Hex(raw);
    }

    /**
     * Builds the FIFO deduplication id from the execution identity, the emitted timestamp, and the full rendered body.
     * Because the body is folded in, two distinct {@code ON_CHANGE} snapshots emitted within the same timestamp
     * granularity produce different ids (SQS will not drop the second inside its dedup window), while an exact
     * redelivery of the identical emission produces the same id and is de-duplicated. The SHA-256 hex digest is 64
     * characters, so the result is always within SQS's 128-character limit regardless of body size.
     */
    private static String deduplicationId(String executionArn, String emittedAt, String body) {
        String arn = executionArn != null ? executionArn : "";
        return sha256Hex(arn + '\u0000' + emittedAt + '\u0000' + body);
    }

    /** Lowercase hex SHA-256 of {@code input}. SHA-256 is a mandated JDK algorithm, so it is always available. */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
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

        /**
         * Maximum serialized size of the rendered JSON <em>body</em>, in bytes, before truncation. Must be positive.
         * Defaults to SQS's 256 KB message limit. SQS counts message attributes toward the same 256 KB quota, so leave
         * headroom below the quota for the {@code status} and {@code functionName} attributes when tuning this.
         */
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
            if (maxRecordSizeBytes != null && maxRecordSizeBytes <= 0) {
                throw new IllegalArgumentException("SQSExporter maxRecordSizeBytes must be positive when set");
            }
            return new SQSExporter(this);
        }
    }
}
