// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the Workflow Insight plugin. Mirrors the JS {@code WorkflowInsightConfig}.
 *
 * @deprecated This is a preview API that is experimental and may be changed or removed in future releases.
 */
@Deprecated
public final class WorkflowInsightConfig {

    /** When records are emitted. */
    public enum EmitMode {
        ON_COMPLETE,
        ON_CHANGE,
        ON_FAILURE
    }

    /** Which operations to include in each record's operations array. */
    public enum OperationDetail {
        TOP_LEVEL,
        FULL_TREE
    }

    private final List<InsightExporter> exporters;
    private final Double samplingRate;
    private final EmitMode emitMode;
    private final OperationDetail operationDetail;
    private final ContentConfig content;

    private WorkflowInsightConfig(Builder b) {
        this.exporters = List.copyOf(b.exporters);
        this.samplingRate = b.samplingRate;
        this.emitMode = b.emitMode;
        this.operationDetail = b.operationDetail;
        this.content = b.content;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<InsightExporter> exporters() {
        return exporters;
    }

    public Double samplingRate() {
        return samplingRate;
    }

    public EmitMode emitMode() {
        return emitMode;
    }

    public OperationDetail operationDetail() {
        return operationDetail;
    }

    public ContentConfig content() {
        return content;
    }

    /** Builder for {@link WorkflowInsightConfig}. */
    public static final class Builder {
        private final List<InsightExporter> exporters = new ArrayList<>();
        private Double samplingRate;
        private EmitMode emitMode;
        private OperationDetail operationDetail;
        private ContentConfig content;

        public Builder addExporter(InsightExporter exporter) {
            this.exporters.add(exporter);
            return this;
        }

        public Builder samplingRate(double rate) {
            this.samplingRate = rate;
            return this;
        }

        public Builder emitMode(EmitMode mode) {
            this.emitMode = mode;
            return this;
        }

        public Builder operationDetail(OperationDetail detail) {
            this.operationDetail = detail;
            return this;
        }

        public Builder content(ContentConfig content) {
            this.content = content;
            return this;
        }

        public WorkflowInsightConfig build() {
            return new WorkflowInsightConfig(this);
        }
    }
}
