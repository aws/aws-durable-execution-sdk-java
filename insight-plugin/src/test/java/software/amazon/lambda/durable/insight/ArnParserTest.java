// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ArnParserTest {

    @Test
    void parsesAllComponentsFromDurableExecutionArn() {
        String arn = "arn:aws:lambda:us-west-2:590183769840:function:my-fn:$LATEST"
                + "/durable-execution/exec-abc/invocation-1";
        ArnParser p = ArnParser.parse(arn);
        assertEquals("us-west-2", p.region());
        assertEquals("590183769840", p.accountId());
        assertEquals("my-fn", p.functionName());
        assertEquals("$LATEST", p.qualifier());
        assertEquals("exec-abc", p.executionName());
    }
}
