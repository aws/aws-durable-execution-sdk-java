// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.insight.exporters.OperationsFormat;

class OperationsFormatTest {

    private WorkflowInsightRecord sampleRecord() {
        WorkflowInsightRecord r = new WorkflowInsightRecord();
        r.executionArn = "arn:aws:lambda:us-east-1:123456789012:function:fn:$LATEST";
        r.status = "SUCCEEDED";
        r.truncated = true;
        r.addOperation(
                new OperationRecord().id("op-1").name("fetch-user").type("STEP").status("SUCCEEDED"));
        r.addOperation(
                new OperationRecord().id("op-2").name("fetch-user").type("STEP").status("FAILED"));
        return r;
    }

    @Test
    void arrayKeepsTheCanonicalOperations() {
        Map<String, Object> data = OperationsFormat.ARRAY.apply(sampleRecord());
        assertEquals(2, ((List<?>) data.get("operations")).size());
        assertFalse(data.containsKey("operationsByName"));
        assertEquals(true, data.get("truncated"));
    }

    @Test
    void byNameReplacesTheArrayWithTheSummaryMap() {
        Map<String, Object> data = OperationsFormat.BY_NAME.apply(sampleRecord());
        assertFalse(data.containsKey("operations"));
        Map<?, ?> summary = (Map<?, ?>) ((Map<?, ?>) data.get("operationsByName")).get("fetch-user");
        assertEquals(2, summary.get("count"));
        assertEquals(1, summary.get("failedCount"));
    }

    @Test
    void bothCarriesTheArrayAndTheMap() {
        Map<String, Object> data = OperationsFormat.BOTH.apply(sampleRecord());
        assertEquals(2, ((List<?>) data.get("operations")).size());
        assertTrue(data.containsKey("operationsByName"));
        List<String> keys = List.copyOf(data.keySet());
        assertEquals("operationsByName", keys.get(keys.size() - 1), "appended after the canonical fields");
    }

    @Test
    void parsesConfigurationStrings() {
        assertEquals(OperationsFormat.ARRAY, OperationsFormat.fromValue("array"));
        assertEquals(OperationsFormat.BY_NAME, OperationsFormat.fromValue("by-name"));
        assertEquals(OperationsFormat.BOTH, OperationsFormat.fromValue("both"));
        assertEquals("by-name", OperationsFormat.BY_NAME.value());
        assertThrows(IllegalArgumentException.class, () -> OperationsFormat.fromValue("map"));
    }
}
