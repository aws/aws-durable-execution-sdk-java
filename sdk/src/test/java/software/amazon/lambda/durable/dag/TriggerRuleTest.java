// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.amazon.lambda.durable.dag.TaskStatus.FAILED;
import static software.amazon.lambda.durable.dag.TaskStatus.SKIPPED;
import static software.amazon.lambda.durable.dag.TaskStatus.SUCCEEDED;

import java.util.List;
import org.junit.jupiter.api.Test;

class TriggerRuleTest {

    @Test
    void emptyUpstreamVacuousCases() {
        assertEquals(true, TriggerRule.ALL_SUCCESS.eval(List.of()));
        assertEquals(false, TriggerRule.ALL_FAILED.eval(List.of()));
        assertEquals(true, TriggerRule.ALL_DONE.eval(List.of()));
        assertEquals(false, TriggerRule.ONE_SUCCESS.eval(List.of()));
        assertEquals(false, TriggerRule.ONE_FAILED.eval(List.of()));
        assertEquals(true, TriggerRule.NONE_FAILED.eval(List.of()));
    }

    @Test
    void allSuccess() {
        assertEquals(true, TriggerRule.ALL_SUCCESS.eval(List.of(SUCCEEDED, SUCCEEDED)));
        assertEquals(false, TriggerRule.ALL_SUCCESS.eval(List.of(SUCCEEDED, FAILED)));
        assertEquals(false, TriggerRule.ALL_SUCCESS.eval(List.of(SUCCEEDED, SKIPPED)));
    }

    @Test
    void allFailed() {
        assertEquals(true, TriggerRule.ALL_FAILED.eval(List.of(FAILED, FAILED)));
        assertEquals(false, TriggerRule.ALL_FAILED.eval(List.of(FAILED, SUCCEEDED)));
    }

    @Test
    void allDone() {
        assertEquals(true, TriggerRule.ALL_DONE.eval(List.of(SUCCEEDED, FAILED, SKIPPED)));
    }

    @Test
    void oneSuccess() {
        assertEquals(true, TriggerRule.ONE_SUCCESS.eval(List.of(FAILED, SUCCEEDED)));
        assertEquals(false, TriggerRule.ONE_SUCCESS.eval(List.of(FAILED, SKIPPED)));
    }

    @Test
    void oneFailed() {
        assertEquals(true, TriggerRule.ONE_FAILED.eval(List.of(SUCCEEDED, FAILED)));
        assertEquals(false, TriggerRule.ONE_FAILED.eval(List.of(SUCCEEDED, SKIPPED)));
    }

    @Test
    void noneFailed() {
        assertEquals(true, TriggerRule.NONE_FAILED.eval(List.of(SUCCEEDED, SKIPPED)));
        assertEquals(false, TriggerRule.NONE_FAILED.eval(List.of(SUCCEEDED, FAILED)));
    }
}
