// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload;

/** Identifies the role of a serialized user payload in a durable execution. */
public enum SerDesPayloadKind {
    INPUT("input"),
    OUTPUT("output"),
    RESULT("result"),
    INVOKE_PAYLOAD("invoke-payload"),
    STATE("state"),
    EXCEPTION("exception");

    private final String entitySuffix;

    SerDesPayloadKind(String entitySuffix) {
        this.entitySuffix = entitySuffix;
    }

    String entitySuffix() {
        return entitySuffix;
    }
}
