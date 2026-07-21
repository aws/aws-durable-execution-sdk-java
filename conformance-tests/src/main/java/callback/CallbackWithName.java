// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package callback;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;

/** 4-2: Callback with explicit name. */
public class CallbackWithName extends DurableHandler<Object, String> {
    @Override
    public String handleRequest(Object input, DurableContext context) {
        var callback = context.createCallback("approval", String.class);
        return callback.get();
    }
}
