// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

final class SerDesContextHolder {
    private static final ThreadLocal<SerDesContext> CURRENT = new ThreadLocal<>();

    private SerDesContextHolder() {}

    static SerDesContext get() {
        return CURRENT.get();
    }

    static void set(SerDesContext context) {
        CURRENT.set(context);
    }

    static void clear() {
        CURRENT.remove();
    }
}
