// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.rdsdata.RdsDataClient;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.rdsdata.model.Field;
import software.amazon.awssdk.services.rdsdata.model.SqlParameter;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records to Amazon Aurora (PostgreSQL or MySQL) through the RDS Data API, upserting one row
 * per execution keyed by execution ARN. Requires the Data API enabled on the cluster, {@code rds-data:ExecuteStatement}
 * and {@code secretsmanager:GetSecretValue}.
 */
@Experimental
public final class AuroraExporter implements InsightExporter {

    /** Database engine; selects the upsert dialect. */
    @Experimental
    public enum Engine {
        POSTGRESQL("postgresql"),
        MYSQL("mysql");

        private final String value;

        Engine(String value) {
            this.value = value;
        }

        /** The configuration string for this engine. */
        public String value() {
            return value;
        }

        /** Parses a configuration string; unknown values are rejected. */
        public static Engine fromValue(String value) {
            for (Engine e : values()) {
                if (e.value.equals(value)) {
                    return e;
                }
            }
            throw new IllegalArgumentException("Unknown engine: \"" + value + "\". Expected postgresql or mysql.");
        }
    }

    private final String resourceArn;
    private final String secretArn;
    private final String database;
    private final String table;
    private final Engine engine;
    private final Integer maxRecordSizeBytes;
    private final LazyClient<RdsDataClient> client;

    private AuroraExporter(Builder b) {
        this.resourceArn = requireNonNull(b.resourceArn, "resourceArn");
        this.secretArn = requireNonNull(b.secretArn, "secretArn");
        this.database = requireNonNull(b.database, "database");
        this.table = SqlIdentifiers.validate(b.table != null ? b.table : "workflow_insight");
        this.engine = requireNonNull(b.engine, "engine");
        this.maxRecordSizeBytes = b.maxRecordSizeBytes != null ? b.maxRecordSizeBytes : 1_000_000;
        this.client = LazyClient.forSdkClient(
                b.client, "rdsdata", "software.amazon.awssdk.services.rdsdata.RdsDataClient", b.region);
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
        RdsDataClient rdsData = client.get();
        Map<String, Object> wire = record.toWireMap();
        String sql = engine == Engine.POSTGRESQL ? buildPostgresUpsert() : buildMysqlUpsert();
        List<SqlParameter> parameters = List.of(
                string("execution_arn", record.executionArn()),
                string("execution_name", record.executionName()),
                string("function_name", record.functionName()),
                string("status", record.status()),
                string("start_time", record.startTime()),
                string("end_time", (String) wire.get("endTime")),
                longValue("duration_ms", (Long) wire.get("durationMs")),
                string("record_json", Json.stringify(wire)),
                string("emitted_at", (String) wire.get("emittedAt")));
        rdsData.executeStatement(ExecuteStatementRequest.builder()
                .resourceArn(resourceArn)
                .secretArn(secretArn)
                .database(database)
                .sql(sql)
                .parameters(parameters)
                .build());
    }

    private static SqlParameter string(String name, String value) {
        Field field = value != null
                ? Field.builder().stringValue(value).build()
                : Field.builder().isNull(true).build();
        return SqlParameter.builder().name(name).value(field).build();
    }

    private static SqlParameter longValue(String name, Long value) {
        Field field = value != null
                ? Field.builder().longValue(value).build()
                : Field.builder().isNull(true).build();
        return SqlParameter.builder().name(name).value(field).build();
    }

    private String buildPostgresUpsert() {
        return "INSERT INTO " + table + "\n"
                + "      (execution_arn, execution_name, function_name, status, start_time, end_time, duration_ms, record_json, emitted_at)\n"
                + "    VALUES\n"
                + "      (:execution_arn, :execution_name, :function_name, :status, :start_time::timestamptz, :end_time::timestamptz, :duration_ms, :record_json::jsonb, :emitted_at::timestamptz)\n"
                + "    ON CONFLICT (execution_arn) DO UPDATE SET\n"
                + "      status = EXCLUDED.status,\n"
                + "      end_time = EXCLUDED.end_time,\n"
                + "      duration_ms = EXCLUDED.duration_ms,\n"
                + "      record_json = EXCLUDED.record_json,\n"
                + "      emitted_at = EXCLUDED.emitted_at";
    }

    private String buildMysqlUpsert() {
        return "INSERT INTO " + table + "\n"
                + "      (execution_arn, execution_name, function_name, status, start_time, end_time, duration_ms, record_json, emitted_at)\n"
                + "    VALUES\n"
                + "      (:execution_arn, :execution_name, :function_name, :status, :start_time, :end_time, :duration_ms, :record_json, :emitted_at)\n"
                + "    ON DUPLICATE KEY UPDATE\n"
                + "      status = VALUES(status),\n"
                + "      end_time = VALUES(end_time),\n"
                + "      duration_ms = VALUES(duration_ms),\n"
                + "      record_json = VALUES(record_json),\n"
                + "      emitted_at = VALUES(emitted_at)";
    }

    /** Builder for {@link AuroraExporter}. */
    public static final class Builder {
        private String resourceArn;
        private String secretArn;
        private String database;
        private String table;
        private Engine engine;
        private String region;
        private Integer maxRecordSizeBytes;
        private RdsDataClient client;

        public Builder resourceArn(String resourceArn) {
            this.resourceArn = resourceArn;
            return this;
        }

        public Builder secretArn(String secretArn) {
            this.secretArn = secretArn;
            return this;
        }

        public Builder database(String database) {
            this.database = database;
            return this;
        }

        /** Table name; letters, digits, and underscores only. Default {@code workflow_insight}. */
        public Builder table(String table) {
            this.table = table;
            return this;
        }

        public Builder engine(Engine engine) {
            this.engine = engine;
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
        public Builder client(RdsDataClient client) {
            this.client = client;
            return this;
        }

        public AuroraExporter build() {
            return new AuroraExporter(this);
        }
    }
}
