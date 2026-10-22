// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;

/**
 * Test-only SDK-package wrapper whose cause can be set, so the exporter's wrapper walk can be given a cycle. It is
 * declared in the SDK exception package so the exporter treats it as an SDK wrapper.
 */
public final class CyclicWrapperForTest extends UnrecoverableDurableExecutionException {
    private Throwable other;

    public CyclicWrapperForTest(String errorType) {
        super(ErrorObject.builder().errorType(errorType).errorMessage("m").build());
    }

    public void setOther(Throwable other) {
        this.other = other;
    }

    @Override
    public synchronized Throwable getCause() {
        return other;
    }
}
