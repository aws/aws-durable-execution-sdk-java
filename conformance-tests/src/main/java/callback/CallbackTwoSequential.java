// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package callback;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 4-17: Two callbacks — sequential create and wait (cbA, wcbA, cbB, wcbB). */
public class CallbackTwoSequential extends DurableHandler<List<String>, Map<String, String>> {
    @Override
    public Map<String, String> handleRequest(List<String> input, DurableContext context) {
        String nameA = input.get(0);
        String nameB = input.get(1);

        var callbackA = context.createCallback(nameA, String.class);
        String resultA = callbackA.get();

        var callbackB = context.createCallback(nameB, String.class);
        String resultB = callbackB.get();

        Map<String, String> response = new HashMap<>();
        response.put("a", resultA);
        response.put("b", resultB);
        return response;
    }
}
