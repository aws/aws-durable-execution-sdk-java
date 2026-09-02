// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import software.amazon.lambda.durable.insight.InsightExporter;
import software.amazon.lambda.durable.insight.Json;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/**
 * Exports workflow insight records to CloudWatch Logs via {@code System.out.println} (Lambda captures stdout to the
 * function's own log group, so this needs no extra IAM). Emits the {@code operationsByName} map as ONE single-line JSON
 * record, mirroring the JS {@code LambdaLogExporter} (which uses {@code console.log}). The conformance CloudWatch sink
 * decodes both raw top-level JSON lines and the Lambda structured-logging envelope.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class LambdaLogExporter implements InsightExporter {
    /** CloudWatch Logs caps a single log event at 256 KB. */
    private final Integer maxRecordSizeBytes;

    public LambdaLogExporter() {
        this(256_000);
    }

    public LambdaLogExporter(Integer maxRecordSizeBytes) {
        this.maxRecordSizeBytes = maxRecordSizeBytes;
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
        System.out.println(Json.stringify(render(record)));
    }
}
