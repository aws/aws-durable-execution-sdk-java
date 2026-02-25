// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import java.nio.charset.StandardCharsets;

// A concrete implementation of Context. We aren't actually going to use most of this so it doesn't matter that it's
// mostly hardcoded.
public class TestContext implements Context {
    @Override
    public String getAwsRequestId() {
        return "dcc80a71-efe9-4820-a289-1205adbdfd64";
    }

    @Override
    public String getLogGroupName() {
        return "aws/lambda/HelloWorldFunction";
    }

    @Override
    public String getLogStreamName() {
        return "$LATEST";
    }

    @Override
    public String getFunctionName() {
        return "HelloWorldFunction";
    }

    @Override
    public String getFunctionVersion() {
        return "$LATEST";
    }

    @Override
    public String getInvokedFunctionArn() {
        return "arn:aws:lambda:us-east-1:012345678912:function:HelloWorldFunction";
    }

    @Override
    public CognitoIdentity getIdentity() {
        return null;
    }

    @Override
    public ClientContext getClientContext() {
        return null;
    }

    @Override
    public int getRemainingTimeInMillis() {
        return 899999;
    }

    @Override
    public int getMemoryLimitInMB() {
        return 512;
    }

    @Override
    public LambdaLogger getLogger() {
        // Log to System.out
        return new LambdaLogger() {
            @Override
            public void log(String message) {
                System.out.println(message);
            }

            @Override
            public void log(byte[] message) {
                log(new String(message, StandardCharsets.UTF_8));
            }
        };
    }
}
