// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import java.time.Duration;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 3-17: Child context with print only (verify no re-execution on replay) */
public class ChildPrintOnly extends DurableHandler<String, String> {

    @Override
    public String handleRequest(String input, DurableContext context) {
        String result = context.runInChildContext("print-child", String.class, child -> {
            System.out.println(input);
            System.out.flush();
            return input;
        });

        context.wait(null, Duration.ofSeconds(1));

        return result;
    }
}
