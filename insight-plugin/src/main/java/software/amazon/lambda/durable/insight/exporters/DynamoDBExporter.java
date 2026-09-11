// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records to Amazon DynamoDB, one PutItem per record keyed by execution ARN. With the default
 * sort key ({@code emittedAt}) every export adds a history item; with the sort key disabled each export overwrites the
 * execution's item. Emits the {@code operationsByName} map. Requires {@code dynamodb:PutItem}.
 */
@Experimental
public final class DynamoDBExporter implements InsightExporter {
    private final String tableName;
    private final String partitionKey;
    private final String sortKey;
    private final Integer maxRecordSizeBytes;
    private final LazyClient<DynamoDbClient> client;

    private DynamoDBExporter(Builder b) {
        this.tableName = requireNonNull(b.tableName, "tableName");
        this.partitionKey = b.partitionKey != null ? b.partitionKey : "pk";
        String sk = b.sortKey != null ? b.sortKey : "sk";
        this.sortKey = sk.isEmpty() ? null : sk;
        this.maxRecordSizeBytes = b.maxRecordSizeBytes != null ? b.maxRecordSizeBytes : 400_000;
        this.client = LazyClient.forSdkClient(
                b.client, "dynamodb", "software.amazon.awssdk.services.dynamodb.DynamoDbClient", b.region);
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
        Map<String, Object> item = record.toByNameWireMap();
        item.put(partitionKey, record.executionArn());
        if (sortKey != null) {
            item.put(sortKey, item.get("emittedAt"));
        }
        client.get()
                .putItem(PutItemRequest.builder()
                        .tableName(tableName)
                        .item(marshalMap(item))
                        .build());
    }

    private static Map<String, AttributeValue> marshalMap(Map<?, ?> map) {
        Map<String, AttributeValue> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            out.put(String.valueOf(e.getKey()), marshal(e.getValue()));
        }
        return out;
    }

    private static AttributeValue marshal(Object value) {
        Object v = Json.toJsonValue(value);
        if (v == null) {
            return AttributeValue.builder().nul(true).build();
        }
        if (v instanceof String s) {
            return AttributeValue.builder().s(s).build();
        }
        if (v instanceof Boolean bool) {
            return AttributeValue.builder().bool(bool).build();
        }
        if (v instanceof Number n) {
            return AttributeValue.builder().n(n.toString()).build();
        }
        if (v instanceof Map<?, ?> m) {
            return AttributeValue.builder().m(marshalMap(m)).build();
        }
        if (v instanceof List<?> list) {
            List<AttributeValue> items = new ArrayList<>(list.size());
            for (Object o : list) {
                items.add(marshal(o));
            }
            return AttributeValue.builder().l(items).build();
        }
        return AttributeValue.builder().s(v.toString()).build();
    }

    /** Builder for {@link DynamoDBExporter}. */
    public static final class Builder {
        private String tableName;
        private String partitionKey;
        private String sortKey;
        private String region;
        private Integer maxRecordSizeBytes;
        private DynamoDbClient client;

        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        /** Partition key attribute name; its value is the execution ARN. Default {@code pk}. */
        public Builder partitionKey(String partitionKey) {
            this.partitionKey = partitionKey;
            return this;
        }

        /** Sort key attribute name; its value is {@code emittedAt}. Default {@code sk}; an empty string disables it. */
        public Builder sortKey(String sortKey) {
            this.sortKey = sortKey;
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
        public Builder client(DynamoDbClient client) {
            this.client = client;
            return this;
        }

        public DynamoDBExporter build() {
            return new DynamoDBExporter(this);
        }
    }
}
