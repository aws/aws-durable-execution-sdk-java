// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.callback;

import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.examples.types.ApprovalRequest;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.operation.DurableStepOperation.StepConfig;
import software.amazon.lambda.durable.operation.DurableWaitForCallbackOperation;
import software.amazon.lambda.durable.operation.DurableWaitForCallbackOperation.WaitForCallbackConfig;
import software.amazon.lambda.durable.operation.DurableWaitForCallbackOperation.WaitForCallbackContext;
import software.amazon.lambda.durable.serde.JacksonSerDes;

public class WaitForCallbackFailedExample extends DurableHandler<ApprovalRequest, String> {

    @Override
    public String handleRequest(ApprovalRequest input) {

        String approvalResult;

        try {
            approvalResult = DurableWaitForCallbackOperation.waitForCallback(
                    "preapproval",
                    String.class,
                    () -> {
                        StepContext.getCurrentContext()
                                .getLogger()
                                .info(
                                        "Sending callback {} to preapproval system",
                                        WaitForCallbackContext.getCurrentContext()
                                                .getCallbackId());
                        throw new RuntimeException("Submitter failed with an exception");
                    },
                    WaitForCallbackConfig.builder()
                            .stepConfig(StepConfig.builder()
                                    .serDes(new FailedSerDes())
                                    .build())
                            .build());
        } catch (Exception ex) {
            return ex.getClass().getSimpleName() + ":" + ex.getMessage();
        }

        return approvalResult;
    }

    private static class FailedSerDes extends JacksonSerDes {
        @Override
        public <T> T deserialize(String json, TypeToken<T> typeToken) {
            T result = super.deserialize(json, typeToken);
            if (result instanceof RuntimeException ex) {
                throw new SerDesException("Deserialization failed", ex);
            }
            return result;
        }
    }
}
