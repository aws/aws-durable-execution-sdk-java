// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.serde;

public interface SerDes {
    String serialize(Object value);

    <T> T deserialize(String data, Class<T> type);
}
