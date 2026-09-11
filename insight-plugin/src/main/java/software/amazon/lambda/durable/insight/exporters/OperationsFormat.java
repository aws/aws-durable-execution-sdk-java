// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight.exporters;

import java.util.Map;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.insight.WorkflowInsightRecord;

/** How an exporter renders operations in the emitted record. */
@Experimental
public enum OperationsFormat {
    /** The canonical {@code operations} array (lossless; default). */
    ARRAY("array"),
    /** The {@code operationsByName} map in place of the array. */
    BY_NAME("by-name"),
    /** Both the {@code operations} array and the {@code operationsByName} map. */
    BOTH("both");

    private final String value;

    OperationsFormat(String value) {
        this.value = value;
    }

    /** The configuration string for this format ({@code array}, {@code by-name}, {@code both}). */
    public String value() {
        return value;
    }

    /** Parses a configuration string; unknown values are rejected. */
    public static OperationsFormat fromValue(String value) {
        for (OperationsFormat f : values()) {
            if (f.value.equals(value)) {
                return f;
            }
        }
        throw new IllegalArgumentException(
                "Unknown operationsFormat: \"" + value + "\". Expected array, by-name, or both.");
    }

    /** Renders the record's wire map in this format. */
    public Map<String, Object> apply(WorkflowInsightRecord record) {
        switch (this) {
            case BY_NAME:
                return record.toByNameWireMap();
            case BOTH: {
                Map<String, Object> data = record.toWireMap();
                data.put("operationsByName", record.toByNameWireMap().get("operationsByName"));
                return data;
            }
            case ARRAY:
            default:
                return record.toWireMap();
        }
    }
}
