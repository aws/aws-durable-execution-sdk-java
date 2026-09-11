// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.redshiftdata.RedshiftDataClient;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.redshiftdata.model.SqlParameter;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records to Amazon Redshift (provisioned or Serverless) through the Redshift Data API,
 * upserting one row per execution with a MERGE keyed by execution ARN. The statement is submitted without waiting for
 * completion. Requires {@code redshift-data:ExecuteStatement} plus the credential action for the target
 * ({@code redshift-serverless:GetCredentials}, {@code redshift:GetClusterCredentialsWithIAM}, or
 * {@code secretsmanager:GetSecretValue}).
 */
@Experimental
public final class RedshiftExporter implements InsightExporter {
    private final String database;
    private final String fqTable;
    private final String workgroupName;
    private final String clusterIdentifier;
    private final String dbUser;
    private final String secretArn;
    private final Integer maxRecordSizeBytes;
    private final LazyClient<RedshiftDataClient> client;

    private RedshiftExporter(Builder b) {
        if (b.workgroupName == null && b.clusterIdentifier == null) {
            throw new IllegalArgumentException("RedshiftExporter: provide either workgroupName or clusterIdentifier.");
        }
        if (b.workgroupName != null && b.clusterIdentifier != null) {
            throw new IllegalArgumentException("RedshiftExporter: workgroupName and clusterIdentifier are exclusive.");
        }
        if (b.dbUser != null && b.secretArn != null) {
            throw new IllegalArgumentException("RedshiftExporter: dbUser and secretArn are exclusive.");
        }
        this.database = requireNonNull(b.database, "database");
        String table = SqlIdentifiers.validate(b.table != null ? b.table : "workflow_insight");
        String schema = SqlIdentifiers.validate(b.schema != null ? b.schema : "public");
        this.fqTable = schema + "." + table;
        this.workgroupName = b.workgroupName;
        this.clusterIdentifier = b.clusterIdentifier;
        this.dbUser = b.dbUser;
        this.secretArn = b.secretArn;
        this.maxRecordSizeBytes = b.maxRecordSizeBytes != null ? b.maxRecordSizeBytes : 1_000_000;
        this.client = LazyClient.forSdkClient(
                b.client, "redshiftdata", "software.amazon.awssdk.services.redshiftdata.RedshiftDataClient", b.region);
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
        RedshiftDataClient redshift = client.get();
        Map<String, Object> wire = record.toWireMap();
        List<SqlParameter> parameters = new ArrayList<>();
        parameters.add(param("execution_arn", record.executionArn()));
        parameters.add(param("function_name", record.functionName()));
        parameters.add(param("status", record.status()));
        parameters.add(param("record_json", Json.stringify(wire)));
        parameters.add(param("emitted_at", (String) wire.get("emittedAt")));

        // The Data API rejects NULL and empty-string parameter values, so an absent value becomes a typed NULL
        // literal in the source projection instead of a bound parameter.
        String startTime = record.startTime();
        String startTimeSel = "NULL::timestamptz";
        if (startTime != null) {
            parameters.add(param("start_time", startTime));
            startTimeSel = ":start_time::timestamptz";
        }
        String endTime = (String) wire.get("endTime");
        String endTimeSel = "NULL::timestamptz";
        if (endTime != null) {
            parameters.add(param("end_time", endTime));
            endTimeSel = ":end_time::timestamptz";
        }
        Long durationMs = (Long) wire.get("durationMs");
        String durationSel = "NULL::bigint";
        if (durationMs != null) {
            parameters.add(param("duration_ms", Long.toString(durationMs)));
            durationSel = ":duration_ms::bigint";
        }
        String executionName = record.executionName();
        String execNameSel = "NULL::varchar";
        if (executionName != null) {
            parameters.add(param("execution_name", executionName));
            execNameSel = ":execution_name::varchar";
        }

        redshift.executeStatement(ExecuteStatementRequest.builder()
                .workgroupName(workgroupName)
                .clusterIdentifier(clusterIdentifier)
                .database(database)
                .dbUser(dbUser)
                .secretArn(secretArn)
                .sql(buildMerge(execNameSel, startTimeSel, endTimeSel, durationSel))
                .parameters(parameters)
                .build());
    }

    private static SqlParameter param(String name, String value) {
        return SqlParameter.builder().name(name).value(value).build();
    }

    /**
     * MERGE joins target and source on a source column: Redshift rejects a MERGE whose join key is a parameter or
     * constant. {@code JSON_PARSE} lands the record in a SUPER column and the time fields are cast to TIMESTAMPTZ.
     */
    private String buildMerge(String execNameSel, String startTimeSel, String endTimeSel, String durationSel) {
        return "MERGE INTO " + fqTable + " USING (\n"
                + "      SELECT\n"
                + "        :execution_arn::varchar AS execution_arn,\n"
                + "        " + execNameSel + " AS execution_name,\n"
                + "        :function_name::varchar AS function_name,\n"
                + "        :status::varchar AS status,\n"
                + "        " + startTimeSel + " AS start_time,\n"
                + "        " + endTimeSel + " AS end_time,\n"
                + "        " + durationSel + " AS duration_ms,\n"
                + "        JSON_PARSE(:record_json) AS record_json,\n"
                + "        :emitted_at::timestamptz AS emitted_at\n"
                + "    ) AS src\n"
                + "    ON " + fqTable + ".execution_arn = src.execution_arn\n"
                + "    WHEN MATCHED THEN UPDATE SET\n"
                + "      status = src.status,\n"
                + "      end_time = src.end_time,\n"
                + "      duration_ms = src.duration_ms,\n"
                + "      record_json = src.record_json,\n"
                + "      emitted_at = src.emitted_at\n"
                + "    WHEN NOT MATCHED THEN INSERT\n"
                + "      (execution_arn, execution_name, function_name, status, start_time, end_time, duration_ms, record_json, emitted_at)\n"
                + "    VALUES\n"
                + "      (src.execution_arn, src.execution_name, src.function_name, src.status, src.start_time, src.end_time, src.duration_ms, src.record_json, src.emitted_at)";
    }

    /** Builder for {@link RedshiftExporter}. */
    public static final class Builder {
        private String workgroupName;
        private String clusterIdentifier;
        private String database;
        private String dbUser;
        private String secretArn;
        private String table;
        private String schema;
        private String region;
        private Integer maxRecordSizeBytes;
        private RedshiftDataClient client;

        /** Redshift Serverless workgroup name. One of workgroupName or clusterIdentifier is required. */
        public Builder workgroupName(String workgroupName) {
            this.workgroupName = workgroupName;
            return this;
        }

        /** Provisioned cluster identifier. One of workgroupName or clusterIdentifier is required. */
        public Builder clusterIdentifier(String clusterIdentifier) {
            this.clusterIdentifier = clusterIdentifier;
            return this;
        }

        public Builder database(String database) {
            this.database = database;
            return this;
        }

        /** Database user for provisioned clusters using temporary credentials. */
        public Builder dbUser(String dbUser) {
            this.dbUser = dbUser;
            return this;
        }

        /** Secrets Manager secret ARN; an alternative to dbUser for provisioned clusters. */
        public Builder secretArn(String secretArn) {
            this.secretArn = secretArn;
            return this;
        }

        /** Table name; letters, digits, and underscores only. Default {@code workflow_insight}. */
        public Builder table(String table) {
            this.table = table;
            return this;
        }

        /** Schema name; letters, digits, and underscores only. Default {@code public}. */
        public Builder schema(String schema) {
            this.schema = schema;
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
        public Builder client(RedshiftDataClient client) {
            this.client = client;
            return this;
        }

        public RedshiftExporter build() {
            return new RedshiftExporter(this);
        }
    }
}
