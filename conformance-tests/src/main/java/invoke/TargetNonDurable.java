// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package invoke;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

/** Plain Lambda handler (non-durable) used as an invoke target. */
public class TargetNonDurable implements RequestHandler<Object, String> {

    @Override
    public String handleRequest(Object input, Context context) {
        return "non-durable-result";
    }
}
