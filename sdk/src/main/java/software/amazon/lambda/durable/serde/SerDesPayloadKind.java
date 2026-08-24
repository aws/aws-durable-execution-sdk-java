// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

/** Identifies the durable payload being serialized or deserialized. */
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

    /** Returns the stable suffix used in external payload entity identifiers. */
    public String getEntitySuffix() {
        return entitySuffix;
    }
}
