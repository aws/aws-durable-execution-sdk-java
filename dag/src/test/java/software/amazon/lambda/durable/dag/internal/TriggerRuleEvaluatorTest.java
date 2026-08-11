// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.amazon.lambda.durable.dag.TaskStatus.FAILED;
import static software.amazon.lambda.durable.dag.TaskStatus.SKIPPED;
import static software.amazon.lambda.durable.dag.TaskStatus.SUCCEEDED;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.dag.TriggerRule;

/** Tests the internal trigger-rule evaluator (logic moved off the public {@link TriggerRule} value enum). */
class TriggerRuleEvaluatorTest {

    @Test
    void emptyUpstreamVacuousCases() {
        assertEquals(true, TriggerRuleEvaluator.eval(TriggerRule.ALL_SUCCESS, List.of()));
        assertEquals(false, TriggerRuleEvaluator.eval(TriggerRule.ALL_FAILED, List.of()));
        assertEquals(true, TriggerRuleEvaluator.eval(TriggerRule.ALL_DONE, List.of()));
        assertEquals(false, TriggerRuleEvaluator.eval(TriggerRule.ANY_SUCCESS, List.of()));
        assertEquals(false, TriggerRuleEvaluator.eval(TriggerRule.ANY_FAILED, List.of()));
        assertEquals(true, TriggerRuleEvaluator.eval(TriggerRule.NONE_FAILED, List.of()));
    }

    @Test
    void allSuccess() {
        assertEquals(true, TriggerRuleEvaluator.eval(TriggerRule.ALL_SUCCESS, List.of(SUCCEEDED, SUCCEEDED)));
        assertEquals(false, TriggerRuleEvaluator.eval(TriggerRule.ALL_SUCCESS, List.of(SUCCEEDED, FAILED)));
        assertEquals(false, TriggerRuleEvaluator.eval(TriggerRule.ALL_SUCCESS, List.of(SUCCEEDED, SKIPPED)));
    }

    @Test
    void allFailed() {
        assertEquals(true, TriggerRuleEvaluator.eval(TriggerRule.ALL_FAILED, List.of(FAILED, FAILED)));
        assertEquals(false, TriggerRuleEvaluator.eval(TriggerRule.ALL_FAILED, List.of(FAILED, SUCCEEDED)));
    }

    @Test
    void allDone() {
        assertEquals(true, TriggerRuleEvaluator.eval(TriggerRule.ALL_DONE, List.of(SUCCEEDED, FAILED, SKIPPED)));
    }

    @Test
    void anySuccess() {
        assertEquals(true, TriggerRuleEvaluator.eval(TriggerRule.ANY_SUCCESS, List.of(FAILED, SUCCEEDED)));
        assertEquals(false, TriggerRuleEvaluator.eval(TriggerRule.ANY_SUCCESS, List.of(FAILED, SKIPPED)));
    }

    @Test
    void anyFailed() {
        assertEquals(true, TriggerRuleEvaluator.eval(TriggerRule.ANY_FAILED, List.of(SUCCEEDED, FAILED)));
        assertEquals(false, TriggerRuleEvaluator.eval(TriggerRule.ANY_FAILED, List.of(SUCCEEDED, SKIPPED)));
    }

    @Test
    void noneFailed() {
        assertEquals(true, TriggerRuleEvaluator.eval(TriggerRule.NONE_FAILED, List.of(SUCCEEDED, SKIPPED)));
        assertEquals(false, TriggerRuleEvaluator.eval(TriggerRule.NONE_FAILED, List.of(SUCCEEDED, FAILED)));
    }
}
