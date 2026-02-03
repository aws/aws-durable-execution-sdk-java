// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.util;

import java.util.concurrent.CompletionException;

public class AsyncHelper {
    public static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException) {
            return unwrap(throwable.getCause());
        } else {
            return throwable;
        }
    }
}
