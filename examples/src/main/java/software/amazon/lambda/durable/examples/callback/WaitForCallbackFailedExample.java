// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.callback;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;
import software.amazon.lambda.durable.examples.types.ApprovalRequest;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.serde.JacksonSerDes;

public class WaitForCallbackFailedExample extends DurableHandler<ApprovalRequest, String> {

    @Override
    public String handleRequest(ApprovalRequest input, DurableContext context) {

        String approvalResult;

        try {
            approvalResult = context.waitForCallback(
                    "preapproval",
                    String.class,
                    (callbackId, ctx) -> {
                        ctx.getLogger().info("Sending callback {} to preapproval system", callbackId);
                        throw new RuntimeException("Submitter failed with an exception");
                    },
                    WaitForCallbackConfig.builder()
                            .stepConfig(StepConfig.builder()
                                    .serDes(new FailedSerDes())
                                    .build())
                            .build());
        } catch (Exception ex) {
            // not to swallow the SuspendExecutionException
            if (ex instanceof SuspendExecutionException suspendExecutionException) {
                throw suspendExecutionException;
            }
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
