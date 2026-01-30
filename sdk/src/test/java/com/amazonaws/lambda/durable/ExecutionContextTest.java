// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class ExecutionContextTest {

    @Test
    void constructorSetsArn() {
        var arn = "arn:aws:lambda:us-east-1:123456789012:function:my-function:$LATEST/durable-execution/"
                + "349beff4-a89d-4bc8-a56f-af7a8af67a5f/20dae574-53da-37a1-bfd5-b0e2e6ec715d";
        var context = new ExecutionContext(arn);

        assertEquals(arn, context.getDurableExecutionArn());
    }

    @Test
    void getDurableExecutionArnReturnsCorrectValue() {
        var arn = "arn:aws:lambda:eu-west-1:987654321098:function:test-fn:$LATEST/durable-execution/"
                + "a1b2c3d4-e5f6-7890-abcd-ef1234567890/b2c3d4e5-f6a7-8901-bcde-f12345678901";
        var context = new ExecutionContext(arn);

        assertNotNull(context.getDurableExecutionArn());
        assertEquals(arn, context.getDurableExecutionArn());
    }
}
