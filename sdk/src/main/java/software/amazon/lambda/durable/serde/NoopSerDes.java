// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import software.amazon.lambda.durable.TypeToken;

/** A {@link SerDes} implementation that does nothing. Used as a placeholder when no serialization is required. */
public class NoopSerDes implements SerDes {
    @Override
    public String serialize(Object value) {
        return "";
    }

    @Override
    public <T> T deserialize(String data, TypeToken<T> typeToken) {
        return null;
    }
}
